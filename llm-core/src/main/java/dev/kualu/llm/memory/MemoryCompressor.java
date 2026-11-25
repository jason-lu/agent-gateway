package dev.kualu.llm.memory;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.metrics.TurnTimings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Compresses conversation history so the request payload stays bounded as a
 * conversation grows. Compression runs as a two-stage ladder once the context
 * crosses the token threshold:
 *
 * <ol>
 *   <li><b>Tool digests</b> — every bulky {@code tool} result in the context is
 *       condensed by one summarisation pass and replaced in place (the
 *       {@code toolCallId} stays; the raw text is gone). Conversation turns are
 *       left verbatim. When this alone brings the context back under the
 *       threshold, nothing else is compressed.</li>
 *   <li><b>Conversation fold</b> — only when tool digests are not enough, older
 *       turns are folded into the incremental summary marker at the fold point.</li>
 * </ol>
 *
 * <p>Folding is non-destructive: every raw turn stays in
 * {@link ConversationMemory#messages()} (visible history and persistence);
 * only the summary marker moves forward to the fold point. The bounded view
 * actually sent to the model is {@link ConversationMemory#context()}:
 * {@code [system 原提示][摘要 system][最近的原文轮次]}.
 * The summary message is recognised by the {@link #SUMMARY_PREFIX} marker, not by
 * position. The verbatim tail always starts at a {@code user} turn, so a run is
 * never split and a {@code tool} result is never orphaned. Tool digests are the
 * one destructive step: tool outputs are data dumps rather than conversation,
 * and their raw text is replaced by {@link #TOOL_DIGEST_PREFIX}-marked digests.
 *
 * <p>Compression is an optimisation, not a correctness path: if a summarisation
 * call fails the history is left untouched and the turn proceeds with the full
 * payload.
 */
public final class MemoryCompressor {

    /** Marks the summary system message produced by this compressor. */
    public static final String SUMMARY_PREFIX = "【历史对话摘要】";

    /** Marks a tool result whose raw text was replaced by its digest. */
    public static final String TOOL_DIGEST_PREFIX = "【工具结果摘要】";

    /**
     * Longest raw turn text kept verbatim in the summarisation request.
     * Tool results are abbreviated much harder ({@link #MAX_TOOL_CHARS}):
     * they are search dumps, not conversation, and an unbounded dump was
     * dominating the summariser's input and drowning out the real questions.
     * Tool results already carrying {@link #TOOL_DIGEST_PREFIX} are passed
     * through whole — they are bounded by {@link #MAX_DIGEST_CHARS} and are
     * exactly what the summariser should read.
     */
    static final int MAX_TURN_CHARS = 2000;
    static final int MAX_TOOL_CHARS = 240;

    /**
     * Tool-digest stage limits. {@link #DIGEST_FLOOR_CHARS} is the smallest raw
     * result worth digesting: shorter ones cannot shrink (the marker alone would
     * grow them), so they stay verbatim. Input per result is capped at
     * {@link #MAX_DIGEST_INPUT_CHARS}; digest requests are split into batches so
     * one summarisation call never carries an unbounded dump again.
     */
    static final int MAX_DIGEST_INPUT_CHARS = 8000;
    static final int MAX_DIGEST_CHARS = 400;
    static final int DIGEST_FLOOR_CHARS = 600;
    static final int DIGEST_BATCH_COUNT = 24;
    static final int DIGEST_BATCH_CHARS = 64000;

    /** Rough token weight of one image in a vision turn (a typical VLM tile count). */
    static final int IMAGE_TOKENS = 850;

    /** Name of the classpath resource holding the built-in instruction. */
    static final String DEFAULT_INSTRUCTION_RESOURCE = "summarize-instruction.txt";

    /** Name of the classpath resource holding the built-in tool-digest instruction. */
    static final String DEFAULT_TOOL_DIGEST_INSTRUCTION_RESOURCE = "tool-digest-instruction.txt";

    /** Fallback instruction used when the caller supplies none. */
    public static final String DEFAULT_INSTRUCTION = loadInstruction(DEFAULT_INSTRUCTION_RESOURCE);

    /** Instruction for the tool-result digest pass. */
    public static final String DEFAULT_TOOL_DIGEST_INSTRUCTION =
            loadInstruction(DEFAULT_TOOL_DIGEST_INSTRUCTION_RESOURCE);

    private static String loadInstruction(String resource) {
        try (InputStream in = MemoryCompressor.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource "
                        + MemoryCompressor.class.getPackageName().replace('.', '/') + "/" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MemoryCompressor.class);

    private final Supplier<LlmClient> llm;
    private final int maxTokens;
    private final int keepTokens;
    private final String instruction;
    private final int maxSummaryChars;

    /**
     * @param llm             supplier of the client used for the summarisation call;
     *                        resolved per call so a runtime config change is picked up
     * @param maxTokens       trigger threshold: the context's token occupancy
     *                        (see {@link #contextTokens(ConversationMemory)}); the
     *                        fold runs once a turn pushes the context past it
     * @param keepTokens      token budget for the verbatim tail kept after compression
     * @param instruction     summarisation instruction; {@code null} or blank uses
     *                        {@link #DEFAULT_INSTRUCTION}
     * @param maxSummaryChars hard cap on the stored summary body; longer output is
     *                        truncated so the request payload stays bounded
     * @throws IllegalArgumentException if the thresholds cannot yield a bounded history
     */
    public MemoryCompressor(Supplier<LlmClient> llm, int maxTokens, int keepTokens, String instruction,
            int maxSummaryChars) {
        this.llm = Objects.requireNonNull(llm, "llm");
        if (keepTokens < 1) {
            throw new IllegalArgumentException("keepTokens must be >= 1 (got " + keepTokens + ")");
        }
        if (maxTokens <= keepTokens) {
            throw new IllegalArgumentException(
                    "maxTokens must be > keepTokens (got " + maxTokens + ", keepTokens " + keepTokens + ")");
        }
        if (maxSummaryChars <= 0) {
            throw new IllegalArgumentException("maxSummaryChars must be > 0 (got " + maxSummaryChars + ")");
        }
        this.maxTokens = maxTokens;
        this.keepTokens = keepTokens;
        this.instruction = (instruction == null || instruction.isBlank())
                ? DEFAULT_INSTRUCTION
                : instruction;
        this.maxSummaryChars = maxSummaryChars;
    }

    /** Convenience overload for a fixed client. */
    public MemoryCompressor(LlmClient llm, int maxTokens, int keepTokens, String instruction,
            int maxSummaryChars) {
        this(fixed(llm), maxTokens, keepTokens, instruction, maxSummaryChars);
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

    /**
     * A pending tool-digest pass computed by {@link #prepareToolDigest}: every
     * bulky tool result in the context is packed into one or more summarisation
     * requests, ready for {@link #summarizeToolDigest} and then
     * {@link #applyToolDigest}.
     */
    public record ToolDigestPlan(List<Batch> batches, int maxDigestChars) {
        public ToolDigestPlan {
            batches = List.copyOf(batches);
        }

        /** One summarisation request: the prompt messages and the ids it digests. */
        public record Batch(List<ChatMessage> requestMessages, List<String> toolCallIds) {
            public Batch {
                requestMessages = List.copyOf(requestMessages);
                toolCallIds = List.copyOf(toolCallIds);
            }
        }
    }

    /** One tool result queued for digesting (raw content snapshot). Visible for tests. */
    record ToolTarget(String toolCallId, String toolName, String content) {
    }

    /** Cheap check: does the context's token occupancy exceed the threshold? */
    public boolean shouldCompress(ConversationMemory memory) {
        Objects.requireNonNull(memory, "memory");
        return contextTokens(memory) > maxTokens;
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

        if (contextTokens(memory) <= maxTokens) {
            return null;
        }

        // Newest turns survive while they fit the keepTokens budget (the newest is
        // always kept even alone over budget); then roll back to a user turn so a
        // run is never split and a tool result is never orphaned.
        int tailStartInTurns = tail.size();
        int keptTokens = 0;
        while (tailStartInTurns > 0) {
            int cost = messageTokens(tail.get(tailStartInTurns - 1));
            if (keptTokens + cost > keepTokens && tailStartInTurns < tail.size()) {
                break;
            }
            keptTokens += cost;
            tailStartInTurns--;
        }
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
                ? stripSummaryHeading(all.get(summaryIndex).content().substring(SUMMARY_PREFIX.length()))
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
        log.info("{}: context {} msgs/~{} tok -> {} msgs/~{} tok (folded {} turns, summary {} chars, input {} chars)",
                logPrefix(null), contextSize(all), estimateContextTokens(all),
                contextSize(rebuilt), estimateContextTokens(rebuilt), folded, summaryBody.length(),
                requestChars(plan));
        return true;
    }

    /**
     * Computes the tool-digest plan: every bulky raw {@code tool} result in the
     * current context, packed into bounded summarisation requests. Returns
     * {@code null} when the context is under the threshold or nothing is worth
     * digesting. Results already carrying {@link #TOOL_DIGEST_PREFIX} are skipped,
     * so the stage is idempotent across runs.
     */
    public ToolDigestPlan prepareToolDigest(ConversationMemory memory) {
        Objects.requireNonNull(memory, "memory");
        List<ChatMessage> all = memory.messages();
        if (contextTokens(memory) <= maxTokens) {
            return null;
        }
        Map<String, String> toolNames = new LinkedHashMap<>();
        for (ChatMessage message : all) {
            if (message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    toolNames.put(call.id(), call.name());
                }
            }
        }
        List<ToolTarget> targets = new ArrayList<>();
        for (ChatMessage message : contextMessages(all)) {
            if (!"tool".equals(message.role()) || isToolDigest(message.content())) {
                continue;
            }
            String content = message.content();
            if (content.isBlank() || content.length() <= DIGEST_FLOOR_CHARS) {
                continue;
            }
            targets.add(new ToolTarget(message.toolCallId(), toolNames.get(message.toolCallId()), content));
        }
        if (targets.isEmpty()) {
            return null;
        }

        List<ToolDigestPlan.Batch> batches = new ArrayList<>();
        List<ToolTarget> batch = new ArrayList<>();
        int batchChars = 0;
        for (ToolTarget target : targets) {
            int cost = Math.min(target.content().length(), MAX_DIGEST_INPUT_CHARS);
            if (!batch.isEmpty()
                    && (batch.size() >= DIGEST_BATCH_COUNT || batchChars + cost > DIGEST_BATCH_CHARS)) {
                batches.add(toDigestBatch(batch));
                batch = new ArrayList<>();
                batchChars = 0;
            }
            batch.add(target);
            batchChars += cost;
        }
        batches.add(toDigestBatch(batch));
        return new ToolDigestPlan(batches, MAX_DIGEST_CHARS);
    }

    private static ToolDigestPlan.Batch toDigestBatch(List<ToolTarget> targets) {
        List<String> ids = targets.stream().map(ToolTarget::toolCallId).toList();
        return new ToolDigestPlan.Batch(List.of(
                ChatMessage.system(DEFAULT_TOOL_DIGEST_INSTRUCTION + "\n\nCharacter budget per digest: "
                        + MAX_DIGEST_CHARS + ". Longer digests are truncated."),
                ChatMessage.user(toolDigestPrompt(targets))), ids);
    }

    /**
     * Runs the digesting calls for {@code plan} and returns the parsed digests
     * ({@code toolCallId} → digest body). Ids the summariser failed to echo are
     * simply absent; {@link #applyToolDigest} leaves those results raw. Throws on
     * call failure — the caller decides what that means for the rest of the ladder.
     */
    public Map<String, String> summarizeToolDigest(ToolDigestPlan plan) {
        LlmClient client = llm.get();
        if (client == null) {
            throw new LlmException("LLM is not configured");
        }
        Map<String, String> digests = new LinkedHashMap<>();
        for (ToolDigestPlan.Batch batch : plan.batches()) {
            String reply = client.chat(batch.requestMessages(), plan.maxDigestChars() * batch.toolCallIds().size());
            digests.putAll(parseDigests(reply));
        }
        return digests;
    }

    /**
     * Replaces each matching raw tool result with its digest, keeping the
     * {@code toolCallId} so the tool-call pairing in the request stays valid.
     * Returns {@code false} (history untouched) when no digest matched.
     */
    public boolean applyToolDigest(ConversationMemory memory, ToolDigestPlan plan, Map<String, String> digests) {
        List<ChatMessage> all = memory.messages();
        if (digests == null || digests.isEmpty()) {
            return false;
        }
        List<ChatMessage> rebuilt = new ArrayList<>(all.size());
        int digested = 0;
        int beforeChars = 0;
        int afterChars = 0;
        for (ChatMessage message : all) {
            boolean target = "tool".equals(message.role())
                    && message.toolCallId() != null
                    && digests.containsKey(message.toolCallId())
                    && !isToolDigest(message.content());
            if (!target) {
                rebuilt.add(message);
                continue;
            }
            String body = digests.get(message.toolCallId()).strip();
            beforeChars += message.content().length();
            if (body.length() > plan.maxDigestChars()) {
                body = truncate(body, plan.maxDigestChars());
            }
            String content = TOOL_DIGEST_PREFIX + body;
            rebuilt.add(ChatMessage.tool(content, message.toolCallId()));
            afterChars += content.length();
            digested++;
        }
        if (digested == 0) {
            return false;
        }
        memory.replaceAll(rebuilt);
        log.info("{}: tool digest applied to {} result(s) ({} -> {} chars, {} batches)",
                logPrefix(null), digested, beforeChars, afterChars, plan.batches().size());
        return true;
    }

    /**
     * The stage-2 gate after tool digests: {@code true} when the context is still
     * over the threshold and the conversation fold must run. Uses the text
     * estimate rather than the measured usage, because measured {@code promptTokens}
     * describe the <em>previous</em> request and go stale as soon as in-place
     * digests shrink message contents.
     */
    public boolean stillOverThreshold(ConversationMemory memory) {
        Objects.requireNonNull(memory, "memory");
        return estimateContextTokens(memory.messages()) > maxTokens;
    }

    /**
     * Synchronous ladder: tool digests first; the conversation fold only when
     * digests did not bring the context back under the threshold.
     */
    public boolean maybeCompress(ConversationMemory memory, String context) {
        Objects.requireNonNull(memory, "memory");
        long checkStartedAt = System.nanoTime();
        boolean fired = false;
        int writtenChars = 0;
        try {
            int digestChars = runToolDigest(memory, context);
            if (digestChars > 0) {
                fired = true;
                writtenChars += digestChars;
                if (!stillOverThreshold(memory)) {
                    return true;
                }
            }
            FoldPlan plan = prepare(memory);
            if (plan == null) {
                return fired;
            }
            long startedAt = System.nanoTime();
            String summaryBody;
            try {
                summaryBody = summarize(plan);
            } catch (RuntimeException ex) {
                String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                log.warn("{} FAILED: {}; history left unchanged (size {})",
                        logPrefix(context), reason, memory.messages().size(), ex);
                return fired;
            }
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            if (summaryBody == null || summaryBody.isBlank()) {
                log.warn("{} FAILED: summariser returned blank text; history left unchanged (size {})",
                        logPrefix(context), memory.messages().size());
                return fired;
            }
            boolean folded = apply(memory, plan, summaryBody);
            if (folded) {
                fired = true;
                writtenChars += Math.min(summaryBody.length(), maxSummaryChars);
                log.info("{}: summarisation call {} ms", logPrefix(context), elapsedMs);
            }
            return fired;
        } finally {
            TurnTimings.recordCompress((System.nanoTime() - checkStartedAt) / 1_000_000, fired, writtenChars);
        }
    }

    /**
     * One tool-digest pass (prepare, summarise, apply). Returns the digest chars
     * written, {@code 0} when the stage was skipped or failed — a digest failure
     * never blocks the ladder, the conversation fold still runs.
     */
    private int runToolDigest(ConversationMemory memory, String context) {
        try {
            ToolDigestPlan plan = prepareToolDigest(memory);
            if (plan == null) {
                return 0;
            }
            long startedAt = System.nanoTime();
            Map<String, String> digests = summarizeToolDigest(plan);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            if (digests.isEmpty()) {
                log.warn("{} tool-digest FAILED: no usable digests returned; tool results left unchanged (size {})",
                        logPrefix(context), memory.messages().size());
                return 0;
            }
            if (!applyToolDigest(memory, plan, digests)) {
                return 0;
            }
            log.info("{}: tool-digest call {} ms", logPrefix(context), elapsedMs);
            return digests.values().stream()
                    .mapToInt(d -> Math.min(d.strip().length(), plan.maxDigestChars()))
                    .sum();
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("{} tool-digest FAILED: {}; tool results left unchanged (size {})",
                    logPrefix(context), reason, memory.messages().size(), ex);
            return 0;
        }
    }

    private static int contextSize(List<ChatMessage> all) {
        int systemIndex = !all.isEmpty() && isSystem(all.get(0)) && !isSummary(all.get(0)) ? 0 : -1;
        int summaryIndex = lastSummaryIndex(all);
        int tail = all.size() - (summaryIndex >= 0 ? summaryIndex + 1 : systemIndex + 1);
        return (systemIndex >= 0 ? 1 : 0) + (summaryIndex >= 0 ? 1 : 0) + tail;
    }

    /**
     * The context's token occupancy — the gauge this compressor triggers on.
     * Prefers the newest assistant turn's measured {@code usage.promptTokens}
     * (the whole request that call actually carried, request overhead included);
     * falls back to a text estimate of the bounded context view when the
     * upstream reports no usage (older sessions, silent providers).
     */
    static int contextTokens(ConversationMemory memory) {
        List<ChatMessage> all = memory.messages();
        for (int i = all.size() - 1; i >= 0; i--) {
            ChatMessage message = all.get(i);
            if ("assistant".equals(message.role())
                    && message.usage() != null && message.usage().promptTokens() > 0) {
                return message.usage().promptTokens();
            }
        }
        return estimateContextTokens(all);
    }

    /** Estimated tokens of the context view (system + summary + verbatim tail) of {@code all}. */
    static int estimateContextTokens(List<ChatMessage> all) {
        int systemIndex = !all.isEmpty() && isSystem(all.get(0)) && !isSummary(all.get(0)) ? 0 : -1;
        int summaryIndex = lastSummaryIndex(all);
        int tokens = 0;
        if (systemIndex >= 0) {
            tokens += messageTokens(all.get(systemIndex));
        }
        if (summaryIndex >= 0) {
            tokens += messageTokens(all.get(summaryIndex));
        }
        for (int i = (summaryIndex >= 0 ? summaryIndex + 1 : systemIndex + 1); i < all.size(); i++) {
            tokens += messageTokens(all.get(i));
        }
        return tokens;
    }

    /** One message's estimated weight: text, tool-call arguments, and per-image tokens. */
    static int messageTokens(ChatMessage message) {
        int tokens = estimateTokens(message.content());
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                tokens += estimateTokens(call.arguments());
            }
        }
        if (message.images() != null) {
            tokens += message.images().size() * IMAGE_TOKENS;
        }
        return tokens;
    }

    /**
     * Rough token estimate for a chunk of text: CJK and other wide characters
     * count about one token each, everything else about four characters per
     * token. Not a tokenizer — just a stable pressure gauge for when the
     * upstream reports no usage.
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wide = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) {
                wide++;
            } else {
                other++;
            }
        }
        return wide + (other + 3) / 4;
    }

    private static int lastSummaryIndex(List<ChatMessage> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            if (isSummary(all.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Rough character size of a fold's summarisation request, for diagnosing oversized folds. */
    private static int requestChars(FoldPlan plan) {
        return plan.requestMessages().stream().mapToInt(m -> m.content().length()).sum();
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

    /**
     * Truncates a turn's raw text for the summarisation request, keeping a
     * marker so the summariser knows content was dropped. Tool results go
     * through this with a much smaller cap (see {@link #MAX_TOOL_CHARS}).
     */
    private static String abbreviate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int cut = maxChars;
        if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + "… [truncated " + (text.length() - cut) + " chars]";
    }

    /**
     * Strips the leading markdown heading the summariser is told to open with
     * (see {@code summarize-instruction.txt}). Incremental folds reuse the
     * previous body as input, so the heading must not stack.
     */
    private static String stripSummaryHeading(String summary) {
        String text = summary == null ? "" : summary.stripLeading();
        if (!text.startsWith("#")) {
            return text;
        }
        int nl = text.indexOf('\n');
        if (nl < 0) {
            return "";
        }
        return text.substring(nl + 1).stripLeading();
    }

    /** Builds the summarisation request body. Visible for tests. */
    static String summarizePrompt(String previousSummary, List<ChatMessage> turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Previous summary]\n");
        sb.append(previousSummary == null || previousSummary.isBlank() ? "(none)" : previousSummary);
        sb.append("\n\n[Older turns]\n");
        for (ChatMessage turn : turns) {
            // Raw tool dumps go through hard; already-digested results are bounded
            // and dense — exactly what the summariser should read whole.
            boolean digestedTool = "tool".equals(turn.role()) && isToolDigest(turn.content());
            int cap = "tool".equals(turn.role()) && !digestedTool ? MAX_TOOL_CHARS : MAX_TURN_CHARS;
            sb.append(turn.role()).append(": ").append(abbreviate(turn.content(), cap));
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

    /** Builds the tool-digest request body. Visible for tests. */
    static String toolDigestPrompt(List<ToolTarget> targets) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Tool results]\n");
        for (ToolTarget target : targets) {
            sb.append("(toolCallId=").append(target.toolCallId()).append(')');
            if (target.toolName() != null && !target.toolName().isBlank()) {
                sb.append(" (tool: ").append(target.toolName()).append(')');
            }
            sb.append('\n');
            sb.append(abbreviate(target.content(), MAX_DIGEST_INPUT_CHARS));
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Parses the digester's reply into {@code toolCallId} → digest body. Accepts
     * stray prose or markdown fences around the JSON array; entries without a
     * usable id/digest are dropped. Unparseable replies yield an empty map.
     */
    static Map<String, String> parseDigests(String reply) {
        Map<String, String> out = new LinkedHashMap<>();
        if (reply == null || reply.isBlank()) {
            return out;
        }
        int start = reply.indexOf('[');
        int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return out;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(reply.substring(start, end + 1));
        } catch (JsonSyntaxException ex) {
            return out;
        }
        if (!parsed.isJsonArray()) {
            return out;
        }
        JsonArray array = parsed.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            JsonElement id = object.get("toolCallId");
            JsonElement digest = object.get("digest");
            if (id == null || !id.isJsonPrimitive() || digest == null || !digest.isJsonPrimitive()) {
                continue;
            }
            String key = id.getAsString().strip();
            String body = digest.getAsString().strip();
            if (key.isEmpty() || body.isEmpty()) {
                continue;
            }
            out.put(key, body);
        }
        return out;
    }

    /** The verbatim tail of {@code all}: the turns after the fold point (the model's live context). */
    private static List<ChatMessage> contextMessages(List<ChatMessage> all) {
        int systemIndex = !all.isEmpty() && isSystem(all.get(0)) && !isSummary(all.get(0)) ? 0 : -1;
        int summaryIndex = lastSummaryIndex(all);
        int from = summaryIndex >= 0 ? summaryIndex + 1 : systemIndex + 1;
        return all.subList(from, all.size());
    }

    private static boolean isSystem(ChatMessage message) {
        return "system".equals(message.role());
    }

    private static boolean isSummary(ChatMessage message) {
        return isSystem(message) && message.content().startsWith(SUMMARY_PREFIX);
    }

    private static boolean isToolDigest(String content) {
        return content.startsWith(TOOL_DIGEST_PREFIX);
    }

    private static String logPrefix(String context) {
        return (context == null || context.isBlank())
                ? "memory-compress"
                : "memory-compress[" + context + "]";
    }
}
