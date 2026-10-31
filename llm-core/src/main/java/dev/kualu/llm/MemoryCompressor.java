package dev.kualu.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compresses conversation history into an incremental summary so the request
 * payload stays bounded as a conversation grows.
 *
 * <p>Layout after compression: {@code [system 原提示][摘要 system][最近的原文轮次]}.
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

    private final LlmClient llm;
    private final int maxMessages;
    private final int keepMessages;
    private final String instruction;

    /**
     * @param llm          client used for the summarisation call
     * @param maxMessages  trigger threshold, counted over every stored message
     * @param keepMessages how many recent turns survive verbatim after compression
     * @param instruction  summarisation instruction; {@code null} or blank uses
     *                     {@link #DEFAULT_INSTRUCTION}
     * @throws IllegalArgumentException if the thresholds cannot yield a bounded history
     */
    public MemoryCompressor(LlmClient llm, int maxMessages, int keepMessages, String instruction) {
        this.llm = Objects.requireNonNull(llm, "llm");
        if (keepMessages < 2) {
            throw new IllegalArgumentException("keepMessages must be >= 2 (got " + keepMessages + ")");
        }
        if (maxMessages <= keepMessages + 2) {
            throw new IllegalArgumentException(
                    "maxMessages must be > keepMessages + 2 (got " + maxMessages
                            + ", keepMessages " + keepMessages + ")");
        }
        this.maxMessages = maxMessages;
        this.keepMessages = keepMessages;
        this.instruction = (instruction == null || instruction.isBlank())
                ? DEFAULT_INSTRUCTION
                : instruction;
    }

    /**
     * Compresses {@code memory} in place when it exceeds the threshold.
     *
     * @param context log correlation label ({@code "session=<id>"} on web, {@code "cli"}
     *                on the console); {@code null} drops the bracketed prefix
     * @return {@code true} when history was compressed
     */
    public boolean maybeCompress(ConversationMemory memory, String context) {
        Objects.requireNonNull(memory, "memory");
        List<ChatMessage> all = memory.messages();
        String prefix = logPrefix(context);

        if (all.size() <= maxMessages) {
            return false;
        }

        int systemIndex = -1;
        int summaryIndex = -1;
        int cursor = 0;
        if (cursor < all.size() && isSystem(all.get(cursor)) && !isSummary(all.get(cursor))) {
            systemIndex = cursor++;
        }
        if (cursor < all.size() && isSummary(all.get(cursor))) {
            summaryIndex = cursor++;
        }
        int turnsStart = cursor;

        List<ChatMessage> turns = all.subList(turnsStart, all.size());

        int tailStartInTurns = turns.size() - keepMessages;
        while (tailStartInTurns > 0 && !"user".equals(turns.get(tailStartInTurns).role())) {
            tailStartInTurns--;
        }
        if (tailStartInTurns <= 0) {
            boolean startsAtUser = !turns.isEmpty() && "user".equals(turns.get(0).role());
            log.debug("{} skipped: {} (size {})", prefix,
                    startsAtUser ? "nothing older to fold" : "no user-turn cut point", all.size());
            return false;
        }

        String previousSummary = summaryIndex >= 0
                ? all.get(summaryIndex).content().substring(SUMMARY_PREFIX.length())
                : null;
        List<ChatMessage> dropped = List.copyOf(turns.subList(0, tailStartInTurns));
        List<ChatMessage> tail = List.copyOf(turns.subList(tailStartInTurns, turns.size()));

        long startedAt = System.nanoTime();
        String summaryBody;
        try {
            summaryBody = llm.chat(List.of(
                    ChatMessage.system(instruction),
                    ChatMessage.user(summarizePrompt(previousSummary, dropped))));
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("{} FAILED: {}; history left unchanged (size {})",
                    prefix, reason, all.size(), ex);
            return false;
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        if (summaryBody == null || summaryBody.isBlank()) {
            log.warn("{} FAILED: summariser returned blank text; history left unchanged (size {})",
                    prefix, all.size());
            return false;
        }

        List<ChatMessage> rebuilt = new ArrayList<>(2 + tail.size());
        if (systemIndex >= 0) {
            rebuilt.add(all.get(systemIndex));
        }
        rebuilt.add(buildSummaryMessage(summaryBody));
        rebuilt.addAll(tail);
        memory.replaceAll(rebuilt);

        log.info("{}: size {} -> {} (dropped {}, summary {} chars, {} ms)",
                prefix, all.size(), rebuilt.size(), dropped.size(), summaryBody.length(), elapsedMs);
        return true;
    }

    /**
     * Wraps the summary body into its marker message. If a future backend only
     * accepts a single system message, this is the one place to change.
     */
    private static ChatMessage buildSummaryMessage(String summaryBody) {
        return ChatMessage.system(SUMMARY_PREFIX + summaryBody);
    }

    /** Builds the summarisation request body. Visible for tests. */
    static String summarizePrompt(String previousSummary, List<ChatMessage> turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Previous summary]\n");
        sb.append(previousSummary == null || previousSummary.isBlank() ? "(none)" : previousSummary);
        sb.append("\n\n[Older turns]\n");
        for (ChatMessage turn : turns) {
            sb.append(turn.role()).append(": ").append(turn.content());
            if (turn.toolCallId() != null) {
                sb.append(" (toolCallId=").append(turn.toolCallId()).append(')');
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
