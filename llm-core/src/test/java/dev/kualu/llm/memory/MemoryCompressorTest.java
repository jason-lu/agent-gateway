package dev.kualu.llm.memory;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.llm.metrics.TurnTimings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            return replies.get(Math.min(calls.size() - 1, replies.size() - 1));
        }
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
    void invalidThresholds_areRejected() {
        StubLlm llm = new StubLlm();
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 10, 1, null, 4000));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 10, 0, null, 4000));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 4, 2, null, 4000));
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
}
