package dev.kualu.llm.memory;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.TokenUsage;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.llm.metrics.TurnTimings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCompressorTest {

    /** Stub that records every summarisation call and returns canned text. */
    static final class StubLlm extends LlmClient {
        final List<List<ChatMessage>> calls = new ArrayList<>();
        final List<String> userPayloads = new ArrayList<>();
        final List<Integer> maxTokensCalls = new ArrayList<>();
        List<String> replies = List.of("SUMMARY");
        RuntimeException failure;
        int failCalls;

        StubLlm() {
            super(new LlmConfig("http://localhost", "key", "model"));
        }

        @Override
        public String chat(List<ChatMessage> messages, Integer maxTokens) {
            calls.add(List.copyOf(messages));
            userPayloads.add(messages.get(messages.size() - 1).content());
            maxTokensCalls.add(maxTokens);
            if (failure != null) {
                throw failure;
            }
            if (calls.size() <= failCalls) {
                throw new LlmException("boom " + calls.size(), 500, "body");
            }
            return replies.get(Math.min(calls.size() - 1, replies.size() - 1));
        }
    }

    /** A digester reply covering exactly {@code ids}. */
    private static String digestJson(String... ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"toolCallId\":\"").append(ids[i]).append("\",\"digest\":\"D").append(i).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static ConversationMemory conversation(int exchanges, String systemPrompt) {
        ConversationMemory memory = new ConversationMemory();
        if (systemPrompt != null) {
            memory.addSystem(systemPrompt);
        }
        for (int i = 0; i < exchanges; i++) {
            memory.addUser("u" + i);
            memory.addAssistant("a" + i);
        }
        return memory;
    }

    @Test
    void belowThreshold_doesNothing() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 20, 8, null, 4000);
        ConversationMemory memory = conversation(3, "be brief");

        assertFalse(compressor.maybeCompress(memory, "test"));
        assertTrue(llm.calls.isEmpty());
        assertEquals(7, memory.size());
        assertEquals("u0", memory.messages().get(1).content());
    }

    @Test
    void aboveThreshold_foldsOldTurnsIntoSummary_keepsTail() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 8, 3, null, 4000);
        ConversationMemory memory = conversation(5, "be brief");

        assertTrue(compressor.maybeCompress(memory, "test"));

        // Every raw turn survives in the stored history; the summary marker sits
        // at the fold point.
        List<ChatMessage> after = memory.messages();
        assertEquals(12, after.size());
        assertEquals("system", after.get(0).role());
        assertEquals("be brief", after.get(0).content());
        assertEquals("u0", after.get(1).content());
        assertEquals("a2", after.get(6).content());
        assertEquals("system", after.get(7).role());
        assertTrue(after.get(7).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertTrue(after.get(7).content().contains("SUMMARY"));
        assertEquals("u3", after.get(8).content());

        // The model context only carries the summary and the verbatim tail.
        // keepMessages=3 would start the tail at a3; that is not a user turn, so it
        // rolls back to u3 and the tail is the last four turns.
        List<ChatMessage> context = memory.context();
        assertEquals(6, context.size());
        assertEquals("system", context.get(0).role());
        assertEquals("be brief", context.get(0).content());
        assertEquals("system", context.get(1).role());
        assertTrue(context.get(1).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertEquals("u3", context.get(2).content());
        assertEquals("a3", context.get(3).content());
        assertEquals("u4", context.get(4).content());
        assertEquals("a4", context.get(5).content());
    }

    @Test
    void compression_keepsEveryRawTurnInHistory_onlyContextShrinks() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 8, 3, null, 4000);
        ConversationMemory memory = conversation(5, "be brief");

        assertTrue(compressor.maybeCompress(memory, "test"));

        List<String> contents = memory.messages().stream().map(ChatMessage::content).toList();
        for (int i = 0; i < 5; i++) {
            assertTrue(contents.contains("u" + i), "user turn u" + i + " must survive in the history");
            assertTrue(contents.contains("a" + i), "assistant turn a" + i + " must survive in the history");
        }
        assertTrue(memory.context().size() < memory.messages().size(),
                "only the model context is compressed");
    }

    @Test
    void originalSystemPrompt_isNeverTouchedOrDropped() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(4, "SYSTEM-PROMPT-EXACT");

        compressor.maybeCompress(memory, "test");

        assertEquals("SYSTEM-PROMPT-EXACT", memory.messages().get(0).content());
    }

    @Test
    void withoutSystemPrompt_summaryIsFirstInContext() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(4, null);

        assertTrue(compressor.maybeCompress(memory, "test"));

        List<ChatMessage> context = memory.context();
        assertEquals("system", context.get(0).role());
        assertTrue(context.get(0).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertEquals("u3", context.get(1).content());
        assertEquals("u0", memory.messages().get(0).content());
    }

    @Test
    void cutPointOnTool_rollsBackToEarlierUserTurn() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 3, null, 4000);
        ConversationMemory memory = new ConversationMemory();
        memory.addUser("u0");
        memory.addTool("t0", "call_0");
        memory.addUser("u1");
        memory.addTool("t1", "call_1");
        memory.addUser("u2");
        memory.addTool("t2", "call_2");
        memory.addUser("u3");
        memory.addTool("t3", "call_3");

        assertTrue(compressor.maybeCompress(memory, "test"));

        List<ChatMessage> context = memory.context();
        // keepMessages=3 nominally starts the tail at t2 (index 5); that is not a user
        // turn, so the cut rolls back to u2 and no tool result is orphaned.
        assertEquals("system", context.get(0).role());
        assertEquals("u2", context.get(1).content());
        assertEquals("tool", context.get(2).role());
        assertEquals("t2", context.get(2).content());
        assertEquals("u3", context.get(3).content());
        assertEquals("call_3", context.get(4).toolCallId());
        assertEquals("u0", memory.messages().get(0).content());
    }

    @Test
    void noUserCutPoint_skipsCompression() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 5, 2, null, 4000);
        ConversationMemory memory = new ConversationMemory();
        for (int i = 0; i < 3; i++) {
            memory.addTool("t" + i, "call_" + i);
            memory.addAssistant("a" + i);
        }

        assertFalse(compressor.maybeCompress(memory, "test"));
        assertTrue(llm.calls.isEmpty());
        assertEquals(6, memory.size());
    }

    @Test
    void incremental_secondCallSeesOldSummaryAndAgedOutTurns() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of("FIRST-SUMMARY", "SECOND-SUMMARY");
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(3, "sys");
        // sys + 6 turns = 7 > 6

        assertTrue(compressor.maybeCompress(memory, "test"));
        assertEquals(1, llm.calls.size());

        for (int i = 3; i < 5; i++) {
            memory.addUser("u" + i);
            memory.addAssistant("a" + i);
        }
        // sys + summary + u2 a2 u3 a3 u4 a4 = 8 > 6

        assertTrue(compressor.maybeCompress(memory, "test"));
        assertEquals(2, llm.calls.size());

        String secondPayload = llm.userPayloads.get(1);
        assertTrue(secondPayload.contains("FIRST-SUMMARY"), "previous summary must be passed on");
        assertTrue(secondPayload.contains("u2") && secondPayload.contains("a3"), "aged-out turns must be passed on");
        assertFalse(secondPayload.contains("u0"), "turns already folded into the old summary must not reappear");

        // The history keeps every raw turn and carries exactly one summary marker.
        List<ChatMessage> after = memory.messages();
        assertEquals(12, after.size());
        assertEquals("u0", after.get(1).content());
        assertEquals("a4", after.get(11).content());
        List<ChatMessage> markers = after.stream()
                .filter(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX))
                .toList();
        assertEquals(1, markers.size());
        assertEquals("SECOND-SUMMARY",
                markers.get(0).content().substring(MemoryCompressor.SUMMARY_PREFIX.length()));
        assertEquals("SECOND-SUMMARY", memory.context().get(1).content()
                .substring(MemoryCompressor.SUMMARY_PREFIX.length()));
    }

    @Test
    void summaryFailure_leavesHistoryUntouched() {
        StubLlm llm = new StubLlm();
        llm.failure = new LlmException("boom", 500, "body");
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(4, "sys");
        List<ChatMessage> before = memory.messages();

        assertFalse(compressor.maybeCompress(memory, "test"));
        assertEquals(before, memory.messages());
    }

    @Test
    void blankSummary_isTreatedAsFailure() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of("   ");
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(4, "sys");
        List<ChatMessage> before = memory.messages();

        assertFalse(compressor.maybeCompress(memory, "test"));
        assertEquals(before, memory.messages());
    }

    @Test
    void summaryMessage_isRecognisedByPrefixNotPosition() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("plain system prompt");
        memory.addSystem(MemoryCompressor.SUMMARY_PREFIX + "earlier context");
        for (int i = 0; i < 4; i++) {
            memory.addUser("u" + i);
            memory.addAssistant("a" + i);
        }

        assertTrue(compressor.maybeCompress(memory, "test"));

        String payload = llm.userPayloads.get(0);
        assertTrue(payload.contains("earlier context"), "old summary body is the previous summary");
        assertFalse(payload.contains(MemoryCompressor.SUMMARY_PREFIX + "earlier context"),
                "the prefix itself is stripped");
        assertEquals("plain system prompt", memory.messages().get(0).content());
        assertEquals("u0", memory.messages().get(1).content());
    }

    @Test
    void plainSystemMessage_isNotMistakenForSummary() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("a system prompt that talks about 【历史对话摘要】 in the middle");
        for (int i = 0; i < 4; i++) {
            memory.addUser("u" + i);
            memory.addAssistant("a" + i);
        }

        assertTrue(compressor.maybeCompress(memory, "test"));

        assertFalse(llm.userPayloads.get(0).contains("a system prompt that talks about"));
        assertEquals("a system prompt that talks about 【历史对话摘要】 in the middle",
                memory.messages().get(0).content());
    }

    /**
     * Known limitation: a system prompt that <em>starts</em> with the summary marker is
     * indistinguishable from a summary and gets rewritten on the next compression.
     * The marker is an internal detail; see the spec's Journey log.
     */
    @Test
    void systemPromptStartingWithMarker_isTreatedAsSummary() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem(MemoryCompressor.SUMMARY_PREFIX + "user-authored prompt");
        for (int i = 0; i < 4; i++) {
            memory.addUser("u" + i);
            memory.addAssistant("a" + i);
        }

        assertTrue(compressor.maybeCompress(memory, "test"));

        // The prompt is read as a previous summary and folded away.
        assertTrue(llm.userPayloads.get(0).contains("user-authored prompt"));
        assertEquals("SUMMARY", memory.context().get(0).content()
                .substring(MemoryCompressor.SUMMARY_PREFIX.length()));
        assertEquals("u0", memory.messages().get(0).content());
    }

    @Test
    void summarizePrompt_formatsPreviousSummaryAndTurns() {
        String prompt = MemoryCompressor.summarizePrompt(null, List.of(
                ChatMessage.user("hello"),
                ChatMessage.tool("result", "call_1"),
                ChatMessage.assistantToolCalls("", List.of(new ToolCall("call_2", "exec", "{\"command\":\"ls\"}")))));

        assertTrue(prompt.contains("(none)"));
        assertTrue(prompt.contains("user: hello"));
        assertTrue(prompt.contains("tool: result (toolCallId=call_1)"));
        assertTrue(prompt.contains("(tool call exec: {\"command\":\"ls\"})"));

        String withSummary = MemoryCompressor.summarizePrompt("old body", List.of(ChatMessage.user("x")));
        assertTrue(withSummary.contains("old body"));
        assertFalse(withSummary.contains("(none)"));
    }

    @Test
    void summarizePrompt_abbreviatesToolResults() {
        String big = "r".repeat(1000);
        String prompt = MemoryCompressor.summarizePrompt(null, List.of(ChatMessage.tool(big, "call_1")));

        assertTrue(prompt.contains("tool: " + "r".repeat(MemoryCompressor.MAX_TOOL_CHARS) + "… [truncated "),
                "the tool dump is cut to MAX_TOOL_CHARS with a marker");
        assertTrue(prompt.contains("(toolCallId=call_1)"));
        assertFalse(prompt.contains(big), "the raw dump must not be sent whole");
        assertFalse(prompt.contains("r".repeat(MemoryCompressor.MAX_TOOL_CHARS + 1)),
                "nothing beyond the cap survives");
    }

    @Test
    void summarizePrompt_abbreviatesLongTurns() {
        String big = "u".repeat(3000);
        String prompt = MemoryCompressor.summarizePrompt(null, List.of(ChatMessage.user(big)));

        assertTrue(prompt.contains("user: " + "u".repeat(MemoryCompressor.MAX_TURN_CHARS) + "… [truncated "),
                "user turns are cut to MAX_TURN_CHARS");
        assertFalse(prompt.contains(big));
    }

    @Test
    void incremental_previousSummaryHeadingIsNotStacked() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of("# 摘要\nFIRST-SUMMARY", "SECOND-SUMMARY");
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(3, "sys");

        assertTrue(compressor.maybeCompress(memory, "test"));
        for (int i = 3; i < 5; i++) {
            memory.addUser("u" + i);
            memory.addAssistant("a" + i);
        }
        assertTrue(compressor.maybeCompress(memory, "test"));

        String secondPayload = llm.userPayloads.get(1);
        assertTrue(secondPayload.contains("FIRST-SUMMARY"), "the previous summary body is passed on");
        assertFalse(secondPayload.contains("# 摘要"),
                "the old heading must not leak into the re-summarisation request");
    }

    @Test
    void invalidThresholds_areRejected() {
        StubLlm llm = new StubLlm();
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 10, 0, null, 4000));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 10, -1, null, 4000));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 5, 5, null, 4000));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 2, 5, null, 4000));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, -1, 2, null, 4000));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 10, 2, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 10, 2, null, -1));
        assertThrows(NullPointerException.class, () -> new MemoryCompressor((LlmClient) null, 10, 2, null, 4000));
    }

    @Test
    void blankInstruction_fallsBackToDefault() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, "   ", 4000);
        ConversationMemory memory = conversation(4, "sys");

        compressor.maybeCompress(memory, "test");

        String systemContent = llm.calls.get(0).get(0).content();
        assertTrue(systemContent.startsWith(MemoryCompressor.DEFAULT_INSTRUCTION));
        assertTrue(systemContent.contains("Character budget: 4000"),
                "the summariser must be told the character budget");
    }

    @Test
    void summarisationCall_carriesMaxTokens() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 300);
        ConversationMemory memory = conversation(4, "sys");

        compressor.maybeCompress(memory, "test");

        assertEquals(List.of(300), llm.maxTokensCalls);
    }

    @Test
    void compressRecordsIntoTurnTimings() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(4, "sys");

        TurnTimings timings = TurnTimings.begin();
        try {
            assertTrue(compressor.maybeCompress(memory, "test"));
            assertTrue(timings.compressFired());
            assertTrue(timings.summaryChars() > 0);
        } finally {
            TurnTimings.clear();
        }
    }

    @Test
    void shouldCompressReflectsContextSize() {
        MemoryCompressor compressor = new MemoryCompressor(new StubLlm(), 6, 2, null, 4000);
        assertFalse(compressor.shouldCompress(conversation(1, "sys")));
        assertTrue(compressor.shouldCompress(conversation(4, "sys")));
    }

    @Test
    void prepareAndApplyFoldWithoutCallingTheModelInline() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(4, "sys");

        MemoryCompressor.FoldPlan plan = compressor.prepare(memory);
        assertTrue(llm.calls.isEmpty(), "prepare must not call the model");
        assertEquals(7, plan.tailStartIndex());

        assertTrue(compressor.apply(memory, plan, "FOLDED-SUMMARY"));
        assertEquals("【历史对话摘要】FOLDED-SUMMARY", memory.context().get(1).content());
        assertEquals(10, memory.messages().size(), "raw turns survive the fold");
    }

    @Test
    void applyKeepsTurnsAppendedWhileTheModelWasThinking() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(4, "sys");

        MemoryCompressor.FoldPlan plan = compressor.prepare(memory);
        memory.addUser("late-question");
        memory.addAssistant("late-answer");

        assertTrue(compressor.apply(memory, plan, "FOLDED-SUMMARY"));
        List<ChatMessage> all = memory.messages();
        assertEquals("late-answer", all.get(all.size() - 1).content());
        assertTrue(memory.context().stream()
                .anyMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)));
    }

    @Test
    void applyIsSkippedWhenTheSummaryMarkerMoved() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 4000);
        ConversationMemory memory = conversation(4, "sys");

        MemoryCompressor.FoldPlan plan = compressor.prepare(memory);
        List<ChatMessage> withForeignMarker = new ArrayList<>(memory.messages());
        withForeignMarker.add(ChatMessage.system(MemoryCompressor.SUMMARY_PREFIX + "other"));
        memory.replaceAll(withForeignMarker);

        assertFalse(compressor.apply(memory, plan, "FOLDED-SUMMARY"));
    }

    @Test
    void summaryAboveCharBudget_isTruncated() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of("0123456789".repeat(20));
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 25);
        ConversationMemory memory = conversation(4, "sys");

        assertTrue(compressor.maybeCompress(memory, "test"));

        String summary = memory.context().get(1).content()
                .substring(MemoryCompressor.SUMMARY_PREFIX.length());
        assertEquals(25, summary.length());
        assertEquals("0123456789012345678901234", summary);
    }

    @Test
    void summaryAtCharBudget_isKeptIntact() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of("0123456789".repeat(2) + "01234");
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null, 25);
        ConversationMemory memory = conversation(4, "sys");

        assertTrue(compressor.maybeCompress(memory, "test"));

        String summary = memory.context().get(1).content()
                .substring(MemoryCompressor.SUMMARY_PREFIX.length());
        assertEquals(25, summary.length());
    }

    @Test
    void defaultInstruction_isLoadedFromItsResource() {
        assertTrue(MemoryCompressor.DEFAULT_INSTRUCTION.contains("working summary"),
                "the instruction must be read from " + MemoryCompressor.DEFAULT_INSTRUCTION_RESOURCE);
        assertFalse(MemoryCompressor.DEFAULT_INSTRUCTION.isBlank());
    }

    // ---- token-driven triggering ---------------------------------------

    @Test
    void measuredUsageDrivesTheTrigger() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 500, 100, null, 4000);

        ConversationMemory measured = new ConversationMemory().replaceAll(List.of(
                ChatMessage.user("hi"),
                ChatMessage.assistant("hello", new TokenUsage(1000, 5, 1005, 0, 0))));
        assertTrue(compressor.shouldCompress(measured), "1000 measured prompt tokens cross a 500 threshold");

        ConversationMemory silent = new ConversationMemory().replaceAll(List.of(
                ChatMessage.user("hi"),
                ChatMessage.assistant("hello")));
        assertFalse(compressor.shouldCompress(silent), "two tiny turns estimate far below 500 tokens");
    }

    @Test
    void measuredUsageWinsOverTheTextEstimate() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 500, 100, null, 4000);
        // The text alone would estimate ~2000 tokens, but the upstream measured a
        // small request (e.g. a server-side cache): the measurement is the truth.
        String fat = "x".repeat(4000);
        ConversationMemory memory = new ConversationMemory().replaceAll(List.of(
                ChatMessage.user(fat),
                ChatMessage.assistant(fat, new TokenUsage(120, 8, 128, 0, 0))));

        assertFalse(compressor.shouldCompress(memory));
    }

    @Test
    void estimatedOccupancyTriggersWhenNoUsageIsReported() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 500, 100, null, 4000);
        String fat = "x".repeat(4000); // ~1000 estimated tokens per turn
        ConversationMemory memory = new ConversationMemory();
        for (int i = 0; i < 4; i++) {
            memory.addUser(fat).addAssistant(fat);
        }

        assertTrue(compressor.shouldCompress(memory));
        assertTrue(compressor.maybeCompress(memory, "test"));

        // Budget=100 keeps one exchange; the cut rolls back to its user turn.
        List<ChatMessage> context = memory.context();
        assertEquals(3, context.size());
        assertTrue(context.get(0).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertTrue(context.get(1).content().startsWith("x"));
        assertTrue(context.get(2).content().startsWith("x"));
    }

    @Test
    void keepTailFitsTheTokenBudget() {
        String chunk = "x".repeat(800); // ~200 tokens per turn

        ConversationMemory leanHistory = new ConversationMemory();
        for (int i = 0; i < 3; i++) {
            leanHistory.addUser(chunk).addAssistant(chunk);
        }
        StubLlm lean = new StubLlm();
        MemoryCompressor leanCompressor = new MemoryCompressor(lean, 500, 150, null, 4000);
        assertTrue(leanCompressor.maybeCompress(leanHistory, "test"));
        // 150-token budget: only the newest exchange survives (cut rolls back to it).
        List<ChatMessage> leanContext = leanHistory.context();
        assertEquals(3, leanContext.size());
        assertTrue(leanContext.get(0).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertEquals(chunk, leanContext.get(1).content());
        assertEquals(chunk, leanContext.get(2).content());

        ConversationMemory roomyHistory = new ConversationMemory();
        for (int i = 0; i < 3; i++) {
            roomyHistory.addUser(chunk).addAssistant(chunk);
        }
        StubLlm roomy = new StubLlm();
        MemoryCompressor roomyCompressor = new MemoryCompressor(roomy, 800, 700, null, 4000);
        assertTrue(roomyCompressor.maybeCompress(roomyHistory, "test"));
        // 700-token budget: the newest two exchanges survive.
        List<ChatMessage> roomyContext = roomyHistory.context();
        assertEquals(5, roomyContext.size());
        assertTrue(roomyContext.get(0).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
    }

    @Test
    void estimateTokens_countsWideAndAsciiCharactersDifferently() {
        assertEquals(0, MemoryCompressor.estimateTokens(null));
        assertEquals(0, MemoryCompressor.estimateTokens(""));
        assertEquals(1, MemoryCompressor.estimateTokens("abc"));
        assertEquals(1, MemoryCompressor.estimateTokens("abcd"));
        assertEquals(2, MemoryCompressor.estimateTokens("abcde"));
        assertEquals(4, MemoryCompressor.estimateTokens("四个汉字"));
        assertEquals(5, MemoryCompressor.estimateTokens("四个汉字abcd"));
    }

    @Test
    void messageTokens_countToolArgumentsAndImages() {
        ChatMessage withCalls = ChatMessage.assistantToolCalls("",
                List.of(new ToolCall("c1", "exec", "{\"command\":\"ls -la /very/long/path\"}")));
        assertTrue(MemoryCompressor.messageTokens(withCalls) >= 8, () -> "got " + MemoryCompressor.messageTokens(withCalls));

        ChatMessage withImages = ChatMessage.user("what", List.of("data:image/png;base64,AAAA"));
        assertEquals(1 + MemoryCompressor.IMAGE_TOKENS, MemoryCompressor.messageTokens(withImages));
    }

    // ---- stage 1: tool digests -----------------------------------------

    @Test
    void toolDigest_underThresholdAfterDigest_skipsTheConversationFold() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of(digestJson("call_0", "call_1", "call_2"));
        MemoryCompressor compressor = new MemoryCompressor(llm, 500, 100, null, 4000);
        String fat = "x".repeat(1000); // ~250 tok per result
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("sys");
        memory.addUser("u0");
        memory.addTool(fat, "call_0");
        memory.addTool(fat, "call_1");
        memory.addTool(fat, "call_2");
        memory.addAssistant("a0");

        assertTrue(compressor.maybeCompress(memory, "test"));
        assertEquals(1, llm.calls.size(), "only the digest stage runs once the tools are digested");

        List<ChatMessage> after = memory.messages();
        assertEquals("u0", after.get(1).content(), "conversation turns stay verbatim");
        assertEquals("a0", after.get(5).content(), "conversation turns stay verbatim");
        assertEquals(MemoryCompressor.TOOL_DIGEST_PREFIX + "D0", after.get(2).content());
        assertEquals(MemoryCompressor.TOOL_DIGEST_PREFIX + "D1", after.get(3).content());
        assertEquals(MemoryCompressor.TOOL_DIGEST_PREFIX + "D2", after.get(4).content());
        assertEquals("call_1", after.get(3).toolCallId(), "the tool-call pairing survives");
        assertTrue(after.stream().noneMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)),
                "the conversation fold must not run when digests already fit");
    }

    @Test
    void toolDigest_stillOverThreshold_foldsTheConversationNext() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of(digestJson("call_1", "call_2"), "FOLD-SUMMARY");
        MemoryCompressor compressor = new MemoryCompressor(llm, 2000, 200, null, 4000);
        String fatTurn = "x".repeat(2000); // ~500 tok
        String fatTool = "y".repeat(1000); // ~250 tok
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("sys");
        memory.addUser(fatTurn);
        memory.addAssistant(fatTurn);
        memory.addUser(fatTurn);
        memory.addTool(fatTool, "call_1");
        memory.addAssistant(fatTurn);
        memory.addUser(fatTurn);
        memory.addTool(fatTool, "call_2");
        memory.addAssistant(fatTurn);

        assertTrue(compressor.maybeCompress(memory, "test"));
        assertEquals(2, llm.calls.size(), "one digest pass, then the fold");
        assertTrue(llm.userPayloads.get(0).contains("(toolCallId=call_1)"), "first call digests the tools");
        assertFalse(llm.userPayloads.get(0).contains("FOLD-SUMMARY"));

        List<ChatMessage> after = memory.messages();
        List<ChatMessage> markers = after.stream()
                .filter(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX))
                .toList();
        assertEquals(1, markers.size(), "the fold lands exactly one summary marker");
        assertTrue(markers.get(0).content().contains("FOLD-SUMMARY"));
        assertTrue(after.get(4).content().startsWith(MemoryCompressor.TOOL_DIGEST_PREFIX),
                "stage 1 digests land before stage 2 folds");
    }

    @Test
    void toolDigest_stopsTheLadderEvenWhenMeasuredUsageIsStale() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of(digestJson("call_0"));
        MemoryCompressor compressor = new MemoryCompressor(llm, 500, 100, null, 4000);
        // The measured usage says 10000 prompt tokens (the request that ran before
        // the digest); the post-digest text estimate is tiny. The ladder must stop.
        ConversationMemory memory = new ConversationMemory().replaceAll(List.of(
                ChatMessage.user("hi"),
                ChatMessage.assistantToolCalls("",
                        List.of(new ToolCall("call_0", "web_search", "{}")),
                        new TokenUsage(10000, 1, 10001, 0, 0)),
                ChatMessage.tool("x".repeat(1000), "call_0"),
                ChatMessage.assistant("done", new TokenUsage(10000, 1, 10001, 0, 0))));

        assertTrue(compressor.shouldCompress(memory), "measured usage crosses the threshold");
        assertTrue(compressor.maybeCompress(memory, "test"));
        assertEquals(1, llm.calls.size(), "the fold is skipped: digests already fit");
        assertTrue(memory.messages().stream()
                .noneMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)));
    }

    @Test
    void toolDigest_skipsSmallAndAlreadyDigestedResults() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 100, 20, null, 4000);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("sys");
        memory.addUser("u0");
        memory.addTool("tiny", "call_0");
        memory.addTool(MemoryCompressor.TOOL_DIGEST_PREFIX + "done", "call_1");
        memory.addTool("x".repeat(1000), "call_2");
        memory.addAssistant("a0");

        MemoryCompressor.ToolDigestPlan plan = compressor.prepareToolDigest(memory);
        assertEquals(1, plan.batches().size());
        assertEquals(List.of("call_2"), plan.batches().get(0).toolCallIds(),
                "terse and already-digested results are left alone");
    }

    @Test
    void toolDigest_nothingWorthDigesting_returnsNull() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 100, 20, null, 4000);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("sys");
        memory.addUser("x".repeat(4000));
        memory.addTool("tiny", "call_0");
        memory.addAssistant("a0");

        assertNull(compressor.prepareToolDigest(memory));
    }

    @Test
    void toolDigestFailure_leavesToolsRaw_foldStillRuns() {
        StubLlm llm = new StubLlm();
        llm.failCalls = 1; // the digest call blows up, the fold call succeeds
        llm.replies = List.of("FOLD-SUMMARY");
        MemoryCompressor compressor = new MemoryCompressor(llm, 2000, 200, null, 4000);
        String fatTurn = "x".repeat(2000);
        String fatTool = "y".repeat(1000);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("sys");
        memory.addUser(fatTurn);
        memory.addTool(fatTool, "call_1");
        memory.addAssistant(fatTurn);
        memory.addUser(fatTurn);
        memory.addAssistant(fatTurn);

        assertTrue(compressor.maybeCompress(memory, "test"));
        assertEquals(2, llm.calls.size(), "the fold runs after the failed digest pass");
        List<ChatMessage> after = memory.messages();
        assertTrue(after.stream()
                        .anyMatch(m -> "tool".equals(m.role()) && m.content().equals(fatTool)),
                "a failed digest leaves the raw tool result untouched");
        assertTrue(after.stream().anyMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)),
                "the conversation fold is not blocked by a failed digest");
    }

    @Test
    void toolDigestUnparseableReply_fallsThroughToTheFold() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of("SUMMARY", "FOLD-SUMMARY");
        MemoryCompressor compressor = new MemoryCompressor(llm, 2000, 200, null, 4000);
        String fatTurn = "x".repeat(2000);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("sys");
        memory.addUser(fatTurn);
        memory.addTool("y".repeat(1000), "call_1");
        memory.addAssistant(fatTurn);
        memory.addUser(fatTurn);
        memory.addAssistant(fatTurn);

        assertTrue(compressor.maybeCompress(memory, "test"));
        assertEquals(2, llm.calls.size());
        List<ChatMessage> after = memory.messages();
        assertTrue(after.stream()
                        .anyMatch(m -> "tool".equals(m.role()) && m.content().startsWith("yyy")),
                "an unparseable digest reply changes nothing");
        assertTrue(after.stream().anyMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)));
    }

    @Test
    void toolDigestPartialIds_leavesUnmatchedResultsRaw() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of(digestJson("call_0"));
        MemoryCompressor compressor = new MemoryCompressor(llm, 600, 100, null, 4000);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("sys");
        memory.addUser("u0");
        memory.addTool("x".repeat(2000), "call_0");
        memory.addTool("y".repeat(2000), "call_1");
        memory.addAssistant("a0");

        assertTrue(compressor.maybeCompress(memory, "test"));
        List<ChatMessage> after = memory.messages();
        assertEquals(MemoryCompressor.TOOL_DIGEST_PREFIX + "D0", after.get(2).content());
        assertEquals("y".repeat(2000), after.get(3).content(),
                "ids the summariser never echoed stay raw");
    }

    @Test
    void toolDigest_isBatchedWhenResultsOverflowOneRequest() {
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem("sys");
        memory.addUser("u0");
        for (int i = 0; i < 25; i++) {
            memory.addTool("x".repeat(1000), "call_" + i);
        }
        memory.addAssistant("a0");

        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 1000, 100, null, 4000);

        MemoryCompressor.ToolDigestPlan plan = compressor.prepareToolDigest(memory);
        assertEquals(2, plan.batches().size(), "25 results split at the batch cap");
        assertEquals(24, plan.batches().get(0).toolCallIds().size());
        assertEquals(List.of("call_24"), plan.batches().get(1).toolCallIds());

        llm.replies = List.of(
                digestJson(plan.batches().get(0).toolCallIds().toArray(String[]::new)),
                digestJson(plan.batches().get(1).toolCallIds().toArray(String[]::new)));
        assertTrue(compressor.maybeCompress(memory, "test"));
        assertEquals(2, llm.calls.size(), "one call per batch, no fold needed");
    }

    @Test
    void toolDigestPrompt_carriesIdsNamesAndAbbreviatesInput() {
        String prompt = MemoryCompressor.toolDigestPrompt(List.of(
                new MemoryCompressor.ToolTarget("call_1", "web_search", "x".repeat(9000))));

        assertTrue(prompt.contains("[Tool results]"));
        assertTrue(prompt.contains("(toolCallId=call_1)"));
        assertTrue(prompt.contains("(tool: web_search)"));
        assertTrue(prompt.contains("x".repeat(MemoryCompressor.MAX_DIGEST_INPUT_CHARS)),
                "the raw result is capped for the digest request");
        assertTrue(prompt.contains("[truncated"));
        assertFalse(prompt.contains("x".repeat(MemoryCompressor.MAX_DIGEST_INPUT_CHARS + 1)));
    }

    @Test
    void summarizePrompt_passesDigestedToolResultsWhole() {
        String digest = MemoryCompressor.TOOL_DIGEST_PREFIX + "d".repeat(300);
        String prompt = MemoryCompressor.summarizePrompt(null, List.of(ChatMessage.tool(digest, "c1")));

        assertTrue(prompt.contains(digest), "digests are already bounded — the fold reads them whole");
    }

    @Test
    void parseDigests_acceptsFencesAndProse_dropsBadEntries() {
        assertEquals(Map.of("c1", "one", "c2", "two"), MemoryCompressor.parseDigests(
                "```json\n[{\"toolCallId\":\"c1\",\"digest\":\"one\"},{\"toolCallId\":\"c2\",\"digest\":\"two\"}]\n```"));
        assertEquals(Map.of("c1", "one"), MemoryCompressor.parseDigests(
                "Here you go: [{\"toolCallId\":\"c1\",\"digest\":\"one\"}] done"));
        assertEquals(Map.of("c1", "one"), MemoryCompressor.parseDigests(
                "[{\"toolCallId\":\"c1\",\"digest\":\"one\"},{\"toolCallId\":\"c2\"},{\"digest\":\"x\"},\"junk\"]"));
    }

    @Test
    void parseDigests_unparseableReplyYieldsEmpty() {
        assertTrue(MemoryCompressor.parseDigests(null).isEmpty());
        assertTrue(MemoryCompressor.parseDigests("").isEmpty());
        assertTrue(MemoryCompressor.parseDigests("SUMMARY").isEmpty());
        assertTrue(MemoryCompressor.parseDigests("[not json]").isEmpty());
    }

    @Test
    void defaultToolDigestInstruction_isLoadedFromItsResource() {
        assertTrue(MemoryCompressor.DEFAULT_TOOL_DIGEST_INSTRUCTION.contains("JSON array"),
                "the instruction must be read from "
                        + MemoryCompressor.DEFAULT_TOOL_DIGEST_INSTRUCTION_RESOURCE);
        assertFalse(MemoryCompressor.DEFAULT_TOOL_DIGEST_INSTRUCTION.isBlank());
    }
}
