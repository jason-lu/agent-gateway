package dev.kualu.llm.tools;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.ConversationMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request-only preamble contract: inserted after the tool policy, ahead of
 * the conversation, and never written into the memory. Memory assembly depends
 * on this — the {@code 【用户记忆】} block must not leak into history or
 * persistence.
 */
class ToolLoopPreambleTest {

    @Test
    void preamble_isInsertedAfterPolicyAndBeforeConversation() {
        ToolLoopTest.ScriptedLlm llm = new ToolLoopTest.ScriptedLlm();
        llm.replyWith("hi");
        ToolLoopTest.RecordingTool tool = new ToolLoopTest.RecordingTool();
        ConversationMemory memory = new ConversationMemory().addSystem("be brief").addUser("earlier");

        ToolLoop loop = new ToolLoop(llm, () -> List.of(tool),
                () -> List.of(ChatMessage.system("【用户记忆】\n- 称呼：Jason")));
        loop.run(memory, "next");

        List<ChatMessage> payload = llm.requests.getFirst();
        assertEquals("system", payload.get(0).role());
        assertEquals(ToolLoop.TOOL_POLICY, payload.get(0).content());
        assertEquals("【用户记忆】\n- 称呼：Jason", payload.get(1).content());
        assertEquals("be brief", payload.get(2).content());
        assertEquals("earlier", payload.get(3).content());
        assertEquals("next", payload.get(4).content());
    }

    @Test
    void preamble_neverEntersMemory() {
        ToolLoopTest.ScriptedLlm llm = new ToolLoopTest.ScriptedLlm();
        llm.replyWith("hi");
        ConversationMemory memory = new ConversationMemory().addSystem("be brief");

        ToolLoop loop = new ToolLoop(llm, List::<AgentTool>of,
                () -> List.of(ChatMessage.system("【用户记忆】\n- 称呼：Jason")));
        loop.run(memory, "next");

        assertTrue(memory.messages().stream()
                .noneMatch(message -> message.content().contains("【用户记忆】")),
                "the memory block must never be stored");
        assertEquals(3, memory.size(), "system + user + assistant");
    }

    @Test
    void emptyPreamble_leavesPayloadUnchanged() {
        ToolLoopTest.ScriptedLlm llm = new ToolLoopTest.ScriptedLlm();
        llm.replyWith("hi");
        ToolLoopTest.RecordingTool tool = new ToolLoopTest.RecordingTool();

        ToolLoop loop = new ToolLoop(llm, () -> List.of(tool), List::of);
        loop.run(new ConversationMemory(), "next");

        List<ChatMessage> payload = llm.requests.getFirst();
        assertEquals(ToolLoop.TOOL_POLICY, payload.get(0).content());
        assertEquals("next", payload.get(1).content());
        assertEquals(2, payload.size());
    }

    @Test
    void preamble_withoutTools_stillInsertedFirst() {
        ToolLoopTest.ScriptedLlm llm = new ToolLoopTest.ScriptedLlm();
        llm.replyWith("hi");

        ToolLoop loop = new ToolLoop(llm, List::<AgentTool>of,
                () -> List.of(ChatMessage.system("memory-block")));
        loop.run(new ConversationMemory(), "next");

        List<ChatMessage> payload = llm.requests.getFirst();
        assertEquals("memory-block", payload.get(0).content());
        assertEquals("next", payload.get(1).content());
    }
}
