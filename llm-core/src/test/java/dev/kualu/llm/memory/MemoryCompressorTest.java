package dev.kualu.llm.memory;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
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
        List<String> replies = List.of("SUMMARY");
        RuntimeException failure;

        StubLlm() {
            super(new LlmConfig("http://localhost", "key", "model"));
        }

        @Override
        public String chat(List<ChatMessage> messages) {
            calls.add(List.copyOf(messages));
            userPayloads.add(messages.get(messages.size() - 1).content());
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
        MemoryCompressor compressor = new MemoryCompressor(llm, 20, 8, null);
        ConversationMemory memory = conversation(3, "be brief");

        assertFalse(compressor.maybeCompress(memory, "test"));
        assertTrue(llm.calls.isEmpty());
        assertEquals(7, memory.size());
        assertEquals("u0", memory.messages().get(1).content());
    }

    @Test
    void aboveThreshold_foldsOldTurnsIntoSummary_keepsTail() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 8, 3, null);
        ConversationMemory memory = conversation(5, "be brief");

        assertTrue(compressor.maybeCompress(memory, "test"));

        List<ChatMessage> after = memory.messages();
        assertEquals("system", after.get(0).role());
        assertEquals("be brief", after.get(0).content());
        assertEquals("system", after.get(1).role());
        assertTrue(after.get(1).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertTrue(after.get(1).content().contains("SUMMARY"));
        // keepMessages=3 would start the tail at a3; that is not a user turn, so it
        // rolls back to u3 and the tail is the last four turns.
        assertEquals(6, after.size());
        assertEquals("u3", after.get(2).content());
        assertEquals("a3", after.get(3).content());
        assertEquals("u4", after.get(4).content());
        assertEquals("a4", after.get(5).content());
    }

    @Test
    void originalSystemPrompt_isNeverTouchedOrDropped() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null);
        ConversationMemory memory = conversation(4, "SYSTEM-PROMPT-EXACT");

        compressor.maybeCompress(memory, "test");

        assertEquals("SYSTEM-PROMPT-EXACT", memory.messages().get(0).content());
    }

    @Test
    void withoutSystemPrompt_summaryIsFirst() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null);
        ConversationMemory memory = conversation(4, null);

        assertTrue(compressor.maybeCompress(memory, "test"));

        List<ChatMessage> after = memory.messages();
        assertEquals("system", after.get(0).role());
        assertTrue(after.get(0).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertEquals("u3", after.get(1).content());
    }

    @Test
    void cutPointOnTool_rollsBackToEarlierUserTurn() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 3, null);
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

        List<ChatMessage> after = memory.messages();
        // keepMessages=3 nominally starts the tail at t2 (index 5); that is not a user
        // turn, so the cut rolls back to u2 and no tool result is orphaned.
        assertEquals("u2", after.get(1).content());
        assertEquals("tool", after.get(2).role());
        assertEquals("t2", after.get(2).content());
        assertEquals("u3", after.get(3).content());
        assertEquals("call_3", after.get(4).toolCallId());
    }

    @Test
    void noUserCutPoint_skipsCompression() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 5, 2, null);
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
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null);
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

        assertEquals("SECOND-SUMMARY",
                memory.messages().get(1).content().substring(MemoryCompressor.SUMMARY_PREFIX.length()));
    }

    @Test
    void summaryFailure_leavesHistoryUntouched() {
        StubLlm llm = new StubLlm();
        llm.failure = new LlmException("boom", 500, "body");
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null);
        ConversationMemory memory = conversation(4, "sys");
        List<ChatMessage> before = memory.messages();

        assertFalse(compressor.maybeCompress(memory, "test"));
        assertEquals(before, memory.messages());
    }

    @Test
    void blankSummary_isTreatedAsFailure() {
        StubLlm llm = new StubLlm();
        llm.replies = List.of("   ");
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null);
        ConversationMemory memory = conversation(4, "sys");
        List<ChatMessage> before = memory.messages();

        assertFalse(compressor.maybeCompress(memory, "test"));
        assertEquals(before, memory.messages());
    }

    @Test
    void summaryMessage_isRecognisedByPrefixNotPosition() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null);
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
    }

    @Test
    void plainSystemMessage_isNotMistakenForSummary() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null);
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
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, null);
        ConversationMemory memory = new ConversationMemory();
        memory.addSystem(MemoryCompressor.SUMMARY_PREFIX + "user-authored prompt");
        for (int i = 0; i < 4; i++) {
            memory.addUser("u" + i);
            memory.addAssistant("a" + i);
        }

        assertTrue(compressor.maybeCompress(memory, "test"));

        // The prompt is read as a previous summary and folded away.
        assertTrue(llm.userPayloads.get(0).contains("user-authored prompt"));
        assertEquals("SUMMARY", memory.messages().get(0).content()
                .substring(MemoryCompressor.SUMMARY_PREFIX.length()));
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
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 10, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 10, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, 4, 2, null));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCompressor(llm, -1, 2, null));
        assertThrows(NullPointerException.class, () -> new MemoryCompressor(null, 10, 2, null));
    }

    @Test
    void blankInstruction_fallsBackToDefault() {
        StubLlm llm = new StubLlm();
        MemoryCompressor compressor = new MemoryCompressor(llm, 6, 2, "   ");
        ConversationMemory memory = conversation(4, "sys");

        compressor.maybeCompress(memory, "test");

        assertEquals(MemoryCompressor.DEFAULT_INSTRUCTION, llm.calls.get(0).get(0).content());
    }

    @Test
    void defaultInstruction_isLoadedFromItsResource() {
        assertTrue(MemoryCompressor.DEFAULT_INSTRUCTION.contains("working summary"),
                "the instruction must be read from " + MemoryCompressor.DEFAULT_INSTRUCTION_RESOURCE);
        assertFalse(MemoryCompressor.DEFAULT_INSTRUCTION.isBlank());
    }
}
