package dev.kualu.llm.moderation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.client.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * L2 semantic layer: one non-streaming classification call to a small/fast model
 * (flash tier — see the spec). Only texts the lexicon layer passed are sent here.
 *
 * <p>Failure policy: never blocks on infrastructure trouble. Timeouts, malformed
 * replies and upstream errors all degrade to "no finding"; a run of consecutive
 * failures opens a 30s circuit so a dead classifier stops adding latency.
 */
public final class LlmModerator implements TextModerator {

    private static final Logger log = LoggerFactory.getLogger(LlmModerator.class);

    /** Longest text sent to the classifier; longer input is truncated (documented cost). */
    static final int MAX_INPUT_CHARS = 2000;

    private static final int FAILURE_THRESHOLD = 3;
    private static final long BREAKER_OPEN_MS = 30_000;

    static final String SYSTEM_PROMPT = """
            你是一个内容安全分类器。判断用户提供的文本是否包含以下违规内容：
            VIOLENCE 暴力恐怖；REACTIONARY 危害国家安全与公共利益；ANTI_HUMANITY 反人类与仇恨言论；
            CRIME 违法犯罪；FRAUD 诈骗与黑产；SELF_HARM 自伤自杀的教唆或方法传播；
            ABUSE 辱骂与低俗。
            注意：SELF_HARM 的求助、倾诉、安抚请求不是违规，只有教唆他人自伤或传播方法才是。
            新闻报道、历史研究、文艺作品引用、医学与代码语境中的相关词汇不是宣扬，视为 NONE。
            只输出一个 JSON 对象，不要输出其他文字：
            {"category":"NONE|VIOLENCE|REACTIONARY|ANTI_HUMANITY|CRIME|FRAUD|SELF_HARM|ABUSE",
             "severity":"HIGH|MEDIUM|LOW","confidence":0.0到1.0,"reason":"简短理由"}
            """;

    private final Supplier<LlmClient> client;
    private final Duration timeout;
    private final double confidenceThreshold;
    private final ModerationPolicy policy;
    private final ExecutorService executor;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long breakerOpenUntil = 0;

    /**
     * @param client              supplies the classifier client per call (runtime provider changes apply)
     * @param timeout             per-call ceiling; on expiry the call is cancelled and degrades
     * @param confidenceThreshold verdicts below this confidence are "unsure" → FLAG, not BLOCK
     */
    public LlmModerator(Supplier<LlmClient> client, Duration timeout, double confidenceThreshold,
            ModerationPolicy policy) {
        this(client, timeout, confidenceThreshold, policy, newExecutor());
    }

    /** Visible for tests: inject the executor. */
    LlmModerator(Supplier<LlmClient> client, Duration timeout, double confidenceThreshold,
            ModerationPolicy policy, ExecutorService executor) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.confidenceThreshold = confidenceThreshold;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "moderation-llm");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Optional<ModerationHit> check(String text, Direction direction) {
        Objects.requireNonNull(text, "text");
        Optional<ModerationVerdict> verdict = classify(text, direction);
        if (verdict.isEmpty()) {
            return Optional.empty();
        }
        ModerationVerdict v = verdict.get();
        if (v.isUnsure(confidenceThreshold)) {
            // Borderline: record for review, never block on an unsure classifier.
            String excerpt = head(text);
            return Optional.of(new ModerationHit(v.category(), v.severity(), Action.FLAG, excerpt, List.of()));
        }
        if (!v.isViolation(confidenceThreshold)) {
            return Optional.empty();
        }
        // Semantic finding carries no located span, so REDACT is impossible — degrade to FLAG.
        Action action = policy.actionFor(v.severity()) == Action.REDACT ? Action.FLAG : policy.actionFor(v.severity());
        return Optional.of(new ModerationHit(v.category(), v.severity(), action, head(text), List.of()));
    }

    /**
     * One classification call. Empty when the classifier is unavailable
     * (breaker open, timeout, upstream failure, malformed reply).
     */
    Optional<ModerationVerdict> classify(String text, Direction direction) {
        if (System.currentTimeMillis() < breakerOpenUntil) {
            return Optional.empty();
        }
        LlmClient llm = client.get();
        if (llm == null) {
            recordFailure();
            return Optional.empty();
        }
        String truncated = text.length() <= MAX_INPUT_CHARS ? text : text.substring(0, MAX_INPUT_CHARS);
        List<ChatMessage> prompt = List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user("方向: " + (direction == Direction.INPUT ? "用户输入" : "模型输出")
                        + "\n文本: " + truncated));
        Future<String> future = executor.submit(() -> llm.chat(prompt));
        try {
            String reply = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            ModerationVerdict verdict = parseVerdict(reply);
            consecutiveFailures.set(0);
            return Optional.of(verdict);
        } catch (TimeoutException ex) {
            future.cancel(true);
            recordFailure();
            log.warn("moderation classifier timed out after {}", timeout);
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Optional.empty();
        } catch (ExecutionException ex) {
            recordFailure();
            log.warn("moderation classifier call failed", ex.getCause());
            return Optional.empty();
        } catch (RuntimeException ex) {
            recordFailure();
            log.warn("moderation classifier reply unparseable", ex);
            return Optional.empty();
        }
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            breakerOpenUntil = System.currentTimeMillis() + BREAKER_OPEN_MS;
            consecutiveFailures.set(0);
            log.warn("moderation classifier circuit open for {} ms", BREAKER_OPEN_MS);
        }
    }

    /** Parses the classifier's JSON object out of the reply; malformed replies count as NONE. */
    static ModerationVerdict parseVerdict(String reply) {
        if (reply == null || reply.isBlank()) {
            return ModerationVerdict.none();
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("classifier reply carries no JSON object");
        }
        JsonObject json = JsonParser.parseString(reply.substring(start, end + 1)).getAsJsonObject();
        Category category = Category.parse(json.has("category") ? json.get("category").getAsString() : null);
        Severity severity = Severity.LOW;
        if (json.has("severity")) {
            try {
                severity = Severity.valueOf(json.get("severity").getAsString().trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // keep LOW
            }
        }
        double confidence = json.has("confidence") ? json.get("confidence").getAsDouble() : 1.0;
        String reason = json.has("reason") ? json.get("reason").getAsString() : "";
        return new ModerationVerdict(category, severity, confidence, reason);
    }

    private static String head(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 20 ? oneLine : oneLine.substring(0, 20) + "…";
    }
}
