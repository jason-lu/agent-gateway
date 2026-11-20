package dev.kualu.web.moderation;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.moderation.Category;
import dev.kualu.llm.moderation.ContentModerator;
import dev.kualu.llm.moderation.Direction;
import dev.kualu.llm.moderation.LexiconModerator;
import dev.kualu.llm.moderation.LlmModerator;
import dev.kualu.llm.moderation.Mode;
import dev.kualu.llm.moderation.ModerationBlockedException;
import dev.kualu.llm.moderation.ModerationHit;
import dev.kualu.llm.moderation.ModerationMessages;
import dev.kualu.llm.moderation.ModerationPolicy;
import dev.kualu.llm.moderation.NormalizedText;
import dev.kualu.llm.moderation.SegmentGate;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.web.llm.LlmSettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Web-side moderation pipeline: the llm-core {@link ContentModerator} plus
 * audit recording. Input and output gates decide the effective text (original,
 * masked, or refusal) and leave one audit row per finding.
 *
 * <p>L1 (lexicon) always runs inline — it is local and instant. L2 (LLM
 * classifier) runs inline only in block mode, where the verdict decides the
 * disposition; in flag mode every verdict is audit-only, so classification is
 * submitted to {@code moderation-audit} and the request path never waits on it.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    /**
     * Floor for the classifier ceiling in flag mode. The configured
     * {@code timeout-ms} is an inline latency budget (800ms default); when
     * classification runs off the request path it can afford a real answer, so
     * the ceiling is raised instead of timing out every call into "no finding".
     */
    private static final long ASYNC_TIMEOUT_FLOOR_MS = 20_000;

    private final ModerationProperties properties;
    private final ModerationEventStore store;
    private final ContentModerator moderator;
    private final ModerationPolicy policy;
    private final ExecutorService auditExecutor;

    public ModerationService(
            ModerationProperties properties,
            ModerationEventStore store,
            LlmSettingsService llmSettings) {
        this.properties = properties;
        this.store = store;
        this.policy = new ModerationPolicy(properties.isFlagMode() ? Mode.FLAG : Mode.BLOCK);
        this.moderator = properties.enabled() ? build(llmSettings, properties, policy) : null;
        this.auditExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "moderation-audit");
            t.setDaemon(true);
            return t;
        });
        if (properties.enabled()) {
            log.info("moderation configured: mode={}, lexicon={}, llm.enabled={}, llm.model={}, llm.timeout={}ms ({})",
                    properties.mode(), properties.lexiconPath(),
                    properties.llm().enabled(), properties.llm().model(),
                    properties.llm().enabled() ? effectiveLlmTimeout(properties).toMillis() : 0,
                    properties.isFlagMode() ? "async audit" : "inline");
            if (properties.isFlagMode()) {
                log.warn("moderation is in gray-release mode (flag): findings are audited but nothing is intercepted");
            }
        } else {
            log.info("moderation configured: enabled=false");
        }
    }

    @PreDestroy
    void shutdown() {
        auditExecutor.shutdown();
    }

    private static ContentModerator build(LlmSettingsService llmSettings, ModerationProperties properties,
            ModerationPolicy policy) {
        LexiconModerator lexicon = new LexiconModerator(policy, properties.lexiconPath());
        LlmModerator classifier = null;
        if (properties.llm().enabled()) {
            classifier = new LlmModerator(
                    () -> {
                        LlmClient client = llmSettings.clientForModel(
                                properties.llm().provider(), properties.llm().model());
                        return client != null ? client : llmSettings.defaultClient();
                    },
                    effectiveLlmTimeout(properties),
                    0.7,
                    policy);
        }
        return new ContentModerator(lexicon, classifier, policy);
    }

    /** See {@link #ASYNC_TIMEOUT_FLOOR_MS}: flag mode classifies off the request path. */
    private static Duration effectiveLlmTimeout(ModerationProperties properties) {
        return properties.isFlagMode()
                ? Duration.ofMillis(Math.max(properties.llm().timeoutMs(), ASYNC_TIMEOUT_FLOOR_MS))
                : properties.llm().timeout();
    }

    public boolean enabled() {
        return moderator != null;
    }

    /**
     * Input gate. On BLOCK: appends {@link ModerationMessages#BLOCKED_USER_PLACEHOLDER}
     * to {@code memory} (the raw text never enters history or the model) and throws.
     * On REDACT: returns the masked text. Otherwise returns {@code content} unchanged.
     * L1 findings are audited before the gate returns; L2 findings in flag mode
     * land when the background classification completes.
     */
    public String gateInput(ConversationMemory memory, String owner, String sessionId, String content) {
        if (moderator == null) {
            return content;
        }
        Optional<ModerationHit> hit = moderator.checkL1(content, Direction.INPUT);
        if (hit.isEmpty()) {
            hit = l2Check(owner, sessionId, Direction.INPUT, content);
        }
        if (hit.isEmpty()) {
            return content;
        }
        ModerationHit finding = hit.get();
        record(owner, sessionId, Direction.INPUT, finding, content);
        switch (finding.action()) {
            case BLOCK -> {
                memory.addUser(ModerationMessages.BLOCKED_USER_PLACEHOLDER);
                throw new ModerationBlockedException(finding, Direction.INPUT);
            }
            case REDACT -> {
                return moderator.redact(content, finding);
            }
            case FLAG -> {
                return content;
            }
        }
        return content;
    }

    /**
     * Output gate for a completed reply. Audits, then rewrites the memory's last
     * assistant turn to the effective text (refusal on BLOCK, masked on REDACT)
     * and returns it. Call under the session lock.
     */
    public String gateOutput(ConversationMemory memory, String owner, String sessionId, String reply) {
        if (moderator == null) {
            return reply;
        }
        Optional<ModerationHit> hit = moderator.checkL1(reply, Direction.OUTPUT);
        if (hit.isEmpty()) {
            hit = l2Check(owner, sessionId, Direction.OUTPUT, reply);
        }
        if (hit.isEmpty()) {
            return reply;
        }
        ModerationHit finding = hit.get();
        record(owner, sessionId, Direction.OUTPUT, finding, reply);
        return switch (finding.action()) {
            case BLOCK -> {
                rewriteLastAssistant(memory, ModerationMessages.OUTPUT_REFUSAL);
                yield ModerationMessages.OUTPUT_REFUSAL;
            }
            case REDACT -> {
                String masked = moderator.redact(reply, finding);
                rewriteLastAssistant(memory, masked);
                yield masked;
            }
            case FLAG -> reply;
        };
    }

    /**
     * L2 semantic check. Block mode needs the verdict inline (it decides the
     * disposition). Flag mode is audit-only: the check is submitted to
     * {@code moderation-audit} and this returns "no finding" at once — the
     * finding is recorded when the verdict lands.
     */
    private Optional<ModerationHit> l2Check(String owner, String sessionId, Direction direction, String text) {
        if (!properties.llm().enabled()) {
            return Optional.empty();
        }
        if (policy.mode() != Mode.FLAG) {
            return moderator.checkL2(text, direction);
        }
        auditExecutor.execute(() -> {
            try {
                moderator.checkL2(text, direction)
                        .ifPresent(hit -> record(owner, sessionId, direction, hit, text));
            } catch (RuntimeException ex) {
                log.warn("async moderation classify failed for session={}", sessionId, ex);
            }
        });
        return Optional.empty();
    }

    /**
     * Segment gate for a streamed reply: L1 per sentence before forwarding.
     * Findings are audited through {@code onFinding}; a BLOCK finding aborts the
     * stream with {@link ModerationBlockedException}.
     *
     * <p>Returns {@code null} in gray-release (flag) mode: nothing is intercepted
     * there, so holding deltas back would only chop the stream. Audit stays intact
     * through the full pre-persist check ({@link #gateOutput}), which records the
     * same findings on the complete reply.
     */
    public SegmentGate segmentGate(Consumer<String> sink, String owner, String sessionId) {
        if (policy.mode() == Mode.FLAG) {
            return null;
        }
        Consumer<ModerationHit> onFinding = hit -> record(owner, sessionId, Direction.OUTPUT, hit, null);
        return new SegmentGate(moderator, sink, onFinding);
    }

    /**
     * After a segment-level BLOCK: ToolLoop has already committed the partial
     * reply; replace it with the standard refusal and audit the hit.
     */
    public void applyOutputBlock(ConversationMemory memory, String owner, String sessionId, ModerationHit hit) {
        record(owner, sessionId, Direction.OUTPUT, hit, null);
        rewriteLastAssistant(memory, ModerationMessages.OUTPUT_REFUSAL);
    }

    /** Writes one audit row; audit failure never changes the disposition. */
    public void record(String owner, String sessionId, Direction direction, ModerationHit hit, String text) {
        try {
            store.record(new ModerationEvent.New(
                    owner, sessionId, direction, hit.category(), hit.severity(), hit.action(),
                    hit.hasSpans() ? "LEXICON" : "LLM",
                    hit.excerpt(),
                    text == null ? null : sha256(NormalizedText.of(text).text()),
                    policy.needsReview(hit)));
        } catch (RuntimeException ex) {
            log.warn("moderation audit write failed for session={}; disposition unchanged", sessionId, ex);
        }
    }

    private static void rewriteLastAssistant(ConversationMemory memory, String newContent) {
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("assistant".equals(message.role())) {
                messages.set(i, new ChatMessage("assistant", newContent, message.toolCallId(), message.toolCalls()));
                memory.replaceAll(messages);
                return;
            }
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
