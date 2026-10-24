package dev.kualu.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryTest {

    @Test
    void withUser_doesNotMutateHistory() {
        ConversationMemory memory = new ConversationMemory()
                .addUser("hi")
                .addAssistant("hello");

        List<ChatMessage> payload = memory.withUser("next");

        assertEquals(2, memory.size());
        assertEquals(3, payload.size());
        assertEquals("user", payload.get(0).role());
        assertEquals("hi", payload.get(0).content());
        assertEquals("assistant", payload.get(1).role());
        assertEquals("hello", payload.get(1).content());
        assertEquals("user", payload.get(2).role());
        assertEquals("next", payload.get(2).content());
    }

    @Test
    void addUserAndAssistant_accumulateInOrder() {
        ConversationMemory memory = new ConversationMemory();
        memory.addUser("a");
        memory.addAssistant("A");
        memory.addUser("b");
        memory.addAssistant("B");

        List<ChatMessage> messages = memory.messages();
        assertEquals(4, messages.size());
        assertEquals("a", messages.get(0).content());
        assertEquals("A", messages.get(1).content());
        assertEquals("b", messages.get(2).content());
        assertEquals("B", messages.get(3).content());
    }

    @Test
    void systemMessage_isStoredFirst() {
        ConversationMemory memory = new ConversationMemory()
                .addSystem("be brief")
                .addUser("hi");

        List<ChatMessage> messages = memory.messages();
        assertEquals("system", messages.get(0).role());
        assertEquals("be brief", messages.get(0).content());
        assertEquals("user", messages.get(1).role());
    }

    @Test
    void clear_dropsAllTurns() {
        ConversationMemory memory = new ConversationMemory()
                .addUser("hi")
                .addAssistant("hello");
        memory.clear();

        assertEquals(0, memory.size());
        assertTrue(memory.messages().isEmpty());
        assertEquals(1, memory.withUser("again").size());
    }

    @Test
    void chatMessage_factories_andValidation() {
        assertEquals("user", ChatMessage.user("x").role());
        assertEquals("assistant", ChatMessage.assistant("x").role());
        assertEquals("system", ChatMessage.system("x").role());
        assertEquals("tool", ChatMessage.tool("x", "call_1").role());
        assertEquals("call_1", ChatMessage.tool("x", "call_1").toolCallId());
        assertEquals(null, ChatMessage.user("x").toolCallId());
        assertThrows(NullPointerException.class, () -> new ChatMessage(null, "x"));
        assertThrows(NullPointerException.class, () -> new ChatMessage("user", null));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(" ", "x"));
        assertThrows(NullPointerException.class, () -> ChatMessage.tool("x", null));
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.tool("x", " "));
    }

    @Test
    void addTool_storesToolCallId() {
        ConversationMemory memory = new ConversationMemory()
                .addUser("run ls")
                .addTool("file1\nfile2", "call_abc");

        List<ChatMessage> messages = memory.messages();
        assertEquals(2, messages.size());
        assertEquals("tool", messages.get(1).role());
        assertEquals("call_abc", messages.get(1).toolCallId());
        assertEquals("file1\nfile2", messages.get(1).content());
    }
}
