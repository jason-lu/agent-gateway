package dev.kualu.llm.metrics;

import dev.kualu.llm.TokenUsage;

/**
 * Per-turn timing collector. One instance is bound to the thread that starts a
 * turn ({@link #begin()}) and inherited by worker threads it spawns, so deep
 * layers (tool loop, compressor, stores) can record into it via {@link #current()}
 * without threading a parameter through every signature. Unbound calls are
 * no-ops, so paths without a reporting layer (CLI) pay nothing.
 *
 * <p>Collects only; reporting (log line / metrics) is the caller's job.
 */
public final class TurnTimings {

    private static final InheritableThreadLocal<TurnTimings> BOUND = new InheritableThreadLocal<>();

    private final long startedNanos = System.nanoTime();

    private boolean loadRecorded;
    private long loadSqlMs;
    private long loadDecodeMs;
    private long loadChars;
    private int loadCount;

    private boolean listRecorded;
    private long listSqlMs;
    private long listDecodeMs;
    private long listChars;

    private boolean compressRecorded;
    private long compressMs;
    private boolean compressFired;
    private int summaryChars;

    private long llmMs;
    private long llmTtftMs = -1;
    private int llmRounds;

    /** Token sums across the turn's model rounds ({@code promptTokens} sums repeat the context per round). */
    private boolean tokensRecorded;
    private long tokenPrompt;
    private long tokenCompletion;
    private long tokenTotal;
    private long tokenReasoning;
    private long tokenCached;

    /** {@code promptTokens} of the last reported round — the context size at end of turn. */
    private long lastPromptTokens;

    private boolean persistRecorded;
    private long persistEncodeMs;
    private long persistWriteMs;
    private long persistChars;

    /** tool name -> {count, total ms}. Linked for stable log order. */
    private final java.util.Map<String, long[]> tools = new java.util.LinkedHashMap<>();

    private TurnTimings() {
    }

    /** Binds a fresh collector to the current thread and returns it. */
    public static TurnTimings begin() {
        TurnTimings timings = new TurnTimings();
        BOUND.set(timings);
        return timings;
    }

    /** The collector bound to the current thread, or {@code null} when none. */
    public static TurnTimings current() {
        return BOUND.get();
    }

    /** Unbinds the collector from the current thread. */
    public static void clear() {
        BOUND.remove();
    }

    // ---- recorders (no-op when unbound) ----

    /** Session load: one find() — SQL fetch, JSON decode, payload size and turn count. */
    public static void recordLoad(long sqlMs, long decodeMs, long chars, int count) {
        TurnTimings t = BOUND.get();
        if (t != null) {
            t.loadRecorded = true;
            t.loadSqlMs = sqlMs;
            t.loadDecodeMs = decodeMs;
            t.loadChars = chars;
            t.loadCount = count;
        }
    }

    /** Session list: listByOwner() — SQL fetch and decode of every session's payload. */
    public static void recordList(long sqlMs, long decodeMs, long chars) {
        TurnTimings t = BOUND.get();
        if (t != null) {
            t.listRecorded = true;
            t.listSqlMs = sqlMs;
            t.listDecodeMs = decodeMs;
            t.listChars = chars;
        }
    }

    /** Memory compression: wall time of the check/call, whether a summary was written, its size. */
    public static void recordCompress(long ms, boolean fired, int chars) {
        TurnTimings t = BOUND.get();
        if (t != null) {
            t.compressRecorded = true;
            t.compressMs = ms;
            t.compressFired = fired;
            t.summaryChars = chars;
        }
    }

    /** One model round-trip inside the tool loop. */
    public static void recordLlmRound(long ms) {
        TurnTimings t = BOUND.get();
        if (t != null) {
            t.llmMs += ms;
            t.llmRounds++;
        }
    }

    /**
     * Token accounting of one model round; rounds sum up to the turn total and
     * the last call's {@code promptTokens} is kept as the end-of-turn context size.
     */
    public static void recordTokenUsage(TokenUsage usage) {
        TurnTimings t = BOUND.get();
        if (t != null && usage != null) {
            t.tokensRecorded = true;
            t.tokenPrompt += usage.promptTokens();
            t.tokenCompletion += usage.completionTokens();
            t.tokenTotal += usage.totalTokens();
            t.tokenReasoning += usage.reasoningTokens();
            t.tokenCached += usage.cachedTokens();
            t.lastPromptTokens = usage.promptTokens();
        }
    }

    /** First streamed delta of the turn; later deltas are ignored. */
    public static void recordFirstDelta() {
        TurnTimings t = BOUND.get();
        if (t != null && t.llmTtftMs < 0) {
            t.llmTtftMs = (System.nanoTime() - t.startedNanos) / 1_000_000;
        }
    }

    /** One tool execution inside the tool loop. */
    public static void recordTool(String name, long ms) {
        TurnTimings t = BOUND.get();
        if (t != null) {
            t.tools.merge(name, new long[] {1, ms},
                    (a, b) -> new long[] {a[0] + b[0], a[1] + b[1]});
        }
    }

    /** Session persist: JSON encode, DB write, payload size. */
    public static void recordPersist(long encodeMs, long writeMs, long chars) {
        TurnTimings t = BOUND.get();
        if (t != null) {
            t.persistRecorded = true;
            t.persistEncodeMs = encodeMs;
            t.persistWriteMs = writeMs;
            t.persistChars = chars;
        }
    }

    // ---- accessors for the reporting layer ----

    public long totalMs() {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    public boolean hasLoad() {
        return loadRecorded;
    }

    public long loadMs() {
        return loadSqlMs + loadDecodeMs;
    }

    public long loadSqlMs() {
        return loadSqlMs;
    }

    public long loadDecodeMs() {
        return loadDecodeMs;
    }

    public long loadChars() {
        return loadChars;
    }

    public int loadCount() {
        return loadCount;
    }

    public boolean hasList() {
        return listRecorded;
    }

    public long listMs() {
        return listSqlMs + listDecodeMs;
    }

    public long listSqlMs() {
        return listSqlMs;
    }

    public long listDecodeMs() {
        return listDecodeMs;
    }

    public long listChars() {
        return listChars;
    }

    public boolean hasCompress() {
        return compressRecorded;
    }

    public long compressMs() {
        return compressMs;
    }

    public boolean compressFired() {
        return compressFired;
    }

    public int summaryChars() {
        return summaryChars;
    }

    public long llmMs() {
        return llmMs;
    }

    /** First-delta latency in ms, or {@code -1} when the turn was not streamed. */
    public long llmTtftMs() {
        return llmTtftMs;
    }

    public int llmRounds() {
        return llmRounds;
    }

    public boolean hasTokens() {
        return tokensRecorded;
    }

    /** The turn's token total summed across model rounds, or {@code null} when none was reported. */
    public TokenUsage tokenUsage() {
        return tokensRecorded
                ? new TokenUsage((int) tokenPrompt, (int) tokenCompletion, (int) tokenTotal,
                        (int) tokenReasoning, (int) tokenCached)
                : null;
    }

    /** {@code promptTokens} of the last reported round (context size at end of turn), or {@code -1}. */
    public long lastPromptTokens() {
        return tokensRecorded ? lastPromptTokens : -1;
    }

    public boolean hasPersist() {
        return persistRecorded;
    }

    public long persistMs() {
        return persistEncodeMs + persistWriteMs;
    }

    public long persistEncodeMs() {
        return persistEncodeMs;
    }

    public long persistWriteMs() {
        return persistWriteMs;
    }

    public long persistChars() {
        return persistChars;
    }

    /** Total wall time of tool executions this turn. */
    public long toolMs() {
        long total = 0;
        for (long[] entry : tools.values()) {
            total += entry[1];
        }
        return total;
    }

    /** Number of tool executions this turn. */
    public int toolCalls() {
        int total = 0;
        for (long[] entry : tools.values()) {
            total += entry[0];
        }
        return total;
    }

    /** Per-tool aggregation for the turn: name to {@code {count, total ms}}. */
    public java.util.Map<String, long[]> toolBreakdown() {
        return java.util.Map.copyOf(tools);
    }

    /** Single-line summary for structured logs; segments that did not run print as {@code -}. */
    public String toLogLine() {
        return "total=" + totalMs() + "ms"
                + " load=" + segment(loadRecorded, loadMs() + "ms(sql=" + loadSqlMs + ",decode=" + loadDecodeMs
                        + ",chars=" + loadChars + ",count=" + loadCount + ")")
                + " list=" + segment(listRecorded, listMs() + "ms(sql=" + listSqlMs + ",decode=" + listDecodeMs
                        + ",chars=" + listChars + ")")
                + " compress=" + segment(compressRecorded,
                        (compressFired ? "fired" : "skipped") + "(" + compressMs + "ms,summaryChars=" + summaryChars + ")")
                + " llm=" + (llmRounds > 0
                        ? llmMs + "ms(ttft=" + (llmTtftMs < 0 ? "-" : llmTtftMs + "ms") + ",rounds=" + llmRounds + ")"
                        : "-")
                + " tokens=" + segment(tokensRecorded, "(prompt=" + tokenPrompt + ",completion=" + tokenCompletion
                        + ",reasoning=" + tokenReasoning + ",cached=" + tokenCached + ",ctx=" + lastPromptTokens + ")")
                + " tools=" + toolsSegment()
                + " persist=" + segment(persistRecorded, persistMs() + "ms(encode=" + persistEncodeMs
                        + ",write=" + persistWriteMs + ",chars=" + persistChars + ")");
    }

    private String toolsSegment() {
        if (tools.isEmpty()) {
            return "-";
        }
        StringBuilder body = new StringBuilder(toolMs() + "ms(count=" + toolCalls() + ":");
        boolean first = true;
        for (var entry : tools.entrySet()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append(entry.getKey()).append('=').append(entry.getValue()[1]);
        }
        return body.append(')').toString();
    }

    private static String segment(boolean recorded, String body) {
        return recorded ? body : "-";
    }
}
