package dev.kualu.llm.memory;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.metrics.TurnTimings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Compresses conversation history into an incremental summary so the request
 * payload stays bounded as a conversation grows.
 *
 * <p>Folding is non-destructive: every raw turn stays in
 * {@link ConversationMemory#messages()} (visible history and persistence);
 * only the summary marker moves forward to the fold point. The bounded view
 * actually sent to the model is {@link ConversationMemory#context()}:
 * {@code [system 原提示][摘要 system][最近的原文轮次]}.
 * The summary message is recognised by the {@link #SUMMARY_PREFIX} marker, not by
 * position. The verbatim tail always starts at a {@code user} turn, so a run is
 * never split and a {@code tool} result is never orphaned.
 *
 * <p>Compression is an optimisation, not a correctness path: if the summarisation
 * call fails the history is left untouched and the turn proceeds with the full
 * payload.
 */
public final class MemoryCompressor {

    /** Marks the summary system message produced by this compressor. */
    public static final String SUMMARY_PREFIX = "【历史对话摘要】";

    /** Name of the classpath resource holding the built-in instruction. */
    static final String DEFAULT_INSTRUCTION_RESOURCE = "summarize-instruction.txt";

    /** Fallback instruction used when the caller supplies none. */
    public static final String DEFAULT_INSTRUCTION = loadDefaultInstruction();

    private static String loadDefaultInstruction() {
        try (InputStream in = MemoryCompressor.class.getResourceAsStream(DEFAULT_INSTRUCTION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource "
                        + MemoryCompressor.class.getPackageName().replace('.', '/') + "/"
                        + DEFAULT_INSTRUCTION_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MemoryCompressor.class);

    private final Supplier<LlmClient> llm;
    private final int maxMessages;
    private final int keepMessages;
    private final String instruction;
    private final int maxSummaryChars;

    /**
     * @param llm             supplier of the client used for the summarisation call;
     *                        resolved per call so a runtime config change is picked up
     * @param maxMessages     trigger threshold, counted over the context sent to
     *                        the model (system + summary + verbatim tail)
     * @param keepMessages    how many recent turns survive verbatim after compression
     * @param instruction     summarisation instruction; {@code null} or blank uses
     *                        {@link #DEFAULT_INSTRUCTION}
     * @param maxSummaryChars hard cap on the stored summary body; longer output is
     *                        truncated so the request payload stays bounded
     * @throws IllegalArgumentException if the thresholds cannot yield a bounded history
     */
    public MemoryCompressor(Supplier<LlmClient> llm, int maxMessages, int keepMessages, String instruction,
            int maxSummaryChars) {
        this.llm = Objects.requireNonNull(llm, "llm");
        if (keepMessages < 2) {
            throw new IllegalArgumentException("keepMessages must be >= 2 (got " + keepMessages + ")");
        }
        if (maxMessages <= keepMessages + 2) {
            throw new IllegalArgumentException(
                    "maxMessages must be > keepMessages + 2 (got " + maxMessages
                            + ", keepMessages " + keepMessages + ")");
        }
        if (maxSummaryChars <= 0) {
            throw new IllegalArgumentException("maxSummaryChars must be > 0 (got " + maxSummaryChars + ")");
        }
        this.maxMessages = maxMessages;
        this.keepMessages = keepMessages;
        this.instruction = (instruction == null || instruction.isBlank())
                ? DEFAULT_INSTRUCTION
                : instruction;
        this.maxSummaryChars = maxSummaryChars;
    }

    /** Convenience overload for a fixed client. */
    public MemoryCompressor(LlmClient llm, int maxMessages, int keepMessages, String instruction,
            int maxSummaryChars) {
        this(fixed(llm), maxMessages, keepMessages, instruction, maxSummaryChars);
    }

    private static Supplier<LlmClient> fixed(LlmClient llm) {
        Objects.requireNonNull(llm, "llm");
        return () -> llm;
    }

    /**
     * Compresses {@code memory} when the context sent to the model exceeds the
     * threshold. Older turns are folded into the summary marker at the fold
     * point; the raw turns themselves stay in {@link ConversationMemory#messages()}.
     *
     * @param context log correlation label ({@code "session=<id>"} on web, {@code "cli"}
     *                on the console); {@code null} drops the bracketed prefix
     * @return {@code true} when the summary was updated
     */
    /**
     * A pending fold computed by {@link #prepare}: the summarisation request is
     * ready to send, and {@link #apply} folds it in once the summary text is back.
     * The plan is computed under the memory lock and applied later; the raw
     * history only ever grows at the tail, so the cut indices stay valid meanwhile.
     */
    public record FoldPlan(
            int summaryIndex,
            int tailStartIndex,
            List<ChatMessage> requestMessages,
            int maxTokens) {
    }

    /** Cheap check: does the context sent to the model exceed the threshold? */
    public boolean shouldCompress(ConversationMemory memory) {
        Objects.requireNonNull(memory, "memory");
        return contextSize(memory.messages()) > maxMessages;
    }

    /**
     * Computes the fold plan (cut point, summarisation request) without calling
     * the model. Returns {@code null} when there is nothing to fold.
     */
    public FoldPlan prepare(ConversationMemory memory) {
        Objects.requireNonNull(memory, "memory");
        List<ChatMessage> all = memory.messages();
        int systemIndex = !all.isEmpty() && isSystem(all.get(0)) && !isSummary(all.get(0)) ? 0 : -1;
        int summaryIndex = lastSummaryIndex(all);
        List<ChatMessage> tail = all.subList(summaryIndex >= 0 ? summaryIndex + 1 : systemIndex + 1, all.size());

        if (contextSize(all) <= maxMessages) {
            return null;
        }

        int tailStartInTurns = tail.size() - keepMessages;
        while (tailStartInTurns > 0 && !"user".equals(tail.get(tailStartInTurns).role())) {
            tailStartInTurns--;
        }
        if (tailStartInTurns <= 0) {
            boolean startsAtUser = !tail.isEmpty() && "user".equals(tail.get(0).role());
            log.debug("{} skipped: {} (size {})", logPrefix(null),
                    startsAtUser ? "nothing older to fold" : "no user-turn cut point", all.size());
            return null;
        }

        String previousSummary = summaryIndex >= 0
                ? all.get(summaryIndex).content().substring(SUMMARY_PREFIX.length())
                : null;
        List<ChatMessage> dropped = List.copyOf(tail.subList(0, tailStartInTurns));
        int tailStartIndex = (summaryIndex >= 0 ? summaryIndex + 1 : systemIndex + 1) + tailStartInTurns;

        return new FoldPlan(
                summaryIndex,
                tailStartIndex,
                List.of(
                        ChatMessage.system(instruction + "\n\nCharacter budget: " + maxSummaryChars
                                + ". Longer output is truncated."),
                        ChatMessage.user(summarizePrompt(previousSummary, dropped))),
                maxSummaryChars);
    }

    /** Runs the summarisation call for {@code plan}. The caller decides where it runs (inline or async). */
    public String summarize(FoldPlan plan) {
        LlmClient client = llm.get();
        if (client == null) {
            throw new LlmException("LLM is not configured");
        }
        return client.chat(plan.requestMessages(), plan.maxTokens());
    }

    /**
     * Folds {@code plan} into the memory with {@code summaryBody} as the new
     * summary. Returns {@code false} (history untouched) when the memory no
     * longer matches the plan — e.g. a concurrent fold already moved the marker.
     */
    public boolean apply(ConversationMemory memory, FoldPlan plan, String summaryBody) {
        List<ChatMessage> all = memory.messages();
        if (summaryBody == null || summaryBody.isBlank()) {
            return false;
        }
        if (all.size() < plan.tailStartIndex() || lastSummaryIndex(all) != plan.summaryIndex()) {
            log.debug("{}: apply skipped, history moved under the plan (size {})", logPrefix(null), all.size());
            return false;
        }
        if (summaryBody.length() > maxSummaryChars) {
            log.debug("{}: summary truncated to {} chars (was {})",
                    logPrefix(null), maxSummaryChars, summaryBody.length());
            summaryBody = truncate(summaryBody, maxSummaryChars);
        }

        List<ChatMessage> rebuilt = new ArrayList<>(all.size() + 1);
        for (int i = 0; i < plan.tailStartIndex(); i++) {
            if (i != plan.summaryIndex()) {
                rebuilt.add(all.get(i));
            }
        }
        rebuilt.add(buildSummaryMessage(summaryBody));
        rebuilt.addAll(all.subList(plan.tailStartIndex(), all.size()));
        memory.replaceAll(rebuilt);

        int foldStart = plan.summaryIndex() >= 0 ? plan.summaryIndex() + 1
                : (!all.isEmpty() && isSystem(all.get(0)) && !isSummary(all.get(0)) ? 1 : 0);
        int folded = plan.tailStartIndex() - foldStart;
        log.info("{}: context {} -> {} (folded {} turns, summary {} chars)",
                logPrefix(null), contextSize(all), contextSize(rebuilt), folded, summaryBody.length());
        return true;
    }

    /** Synchronous fold: prepare, one summarisation call, apply. */
    public boolean maybeCompress(ConversationMemory memory, String context) {
        Objects.requireNonNull(memory, "memory");
        long checkStartedAt = System.nanoTime();
        boolean fired = false;
        int writtenChars = 0;
        try {
            FoldPlan plan = prepare(memory);
            if (plan == null) {
                return false;
            }
            long startedAt = System.nanoTime();
            String summaryBody;
            try {
                summaryBody = summarize(plan);
            } catch (RuntimeException ex) {
                String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                log.warn("{} FAILED: {}; history left unchanged (size {})",
                        logPrefix(context), reason, memory.messages().size(), ex);
                return false;
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            if (summaryBody == null || summaryBody.isBlank()) {
                log.warn("{} FAILED: summariser returned blank text; history left unchanged (size {})",
                        logPrefix(context), memory.messages().size());
                return false;
            }
            fired = apply(memory, plan, summaryBody);
            writtenChars = fired ? Math.min(summaryBody.length(), maxSummaryChars) : 0;
            if (fired) {
                log.info("{}: summarisation call {} ms", logPrefix(context), elapsedMs);
            }
            return fired;
        } finally {
            TurnTimings.recordCompress((System.nanoTime() - checkStartedAt) / 1_000_000, fired, writtenChars);
        }
    }

    private static int contextSize(List<ChatMessage> all) {
        int systemIndex = !all.isEmpty() && isSystem(all.get(0)) && !isSummary(all.get(0)) ? 0 : -1;
        int summaryIndex = lastSummaryIndex(all);
        int tail = all.size() - (summaryIndex >= 0 ? summaryIndex + 1 : systemIndex + 1);
        return (systemIndex >= 0 ? 1 : 0) + (summaryIndex >= 0 ? 1 : 0) + tail;
    }

    private static int lastSummaryIndex(List<ChatMessage> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            if (isSummary(all.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Wraps the summary body into its marker message. If a future backend only
     * accepts a single system message, this is the one place to change.
     */
    private static ChatMessage buildSummaryMessage(String summaryBody) {
        return ChatMessage.system(SUMMARY_PREFIX + summaryBody);
    }

    /** Caps the summary body at {@code maxChars} without splitting a surrogate pair. */
    private static String truncate(String text, int maxChars) {
        int cut = maxChars;
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut);
    }

    /** Builds the summarisation request body. Visible for tests. */
    static String summarizePrompt(String previousSummary, List<ChatMessage> turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Previous summary]\n");
        sb.append(previousSummary == null || previousSummary.isBlank() ? "(none)" : previousSummary);
        sb.append("\n\n[Older turns]\n");
        for (ChatMessage turn : turns) {
            sb.append(turn.role()).append(": ").append(turn.content());
            if (turn.images() != null && !turn.images().isEmpty()) {
                sb.append("（附图 ").append(turn.images().size()).append(" 张）");
            }
            if (turn.toolCallId() != null) {
                sb.append(" (toolCallId=").append(turn.toolCallId()).append(')');
            }
            if (turn.toolCalls() != null) {
                for (ToolCall call : turn.toolCalls()) {
                    sb.append(" (tool call ").append(call.name())
                            .append(": ").append(call.arguments()).append(')');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean isSystem(ChatMessage message) {
        return "system".equals(message.role());
    }

    private static boolean isSummary(ChatMessage message) {
        return isSystem(message) && message.content().startsWith(SUMMARY_PREFIX);
    }

    private static String logPrefix(String context) {
        return (context == null || context.isBlank())
                ? "memory-compress"
                : "memory-compress[" + context + "]";
    }
}
