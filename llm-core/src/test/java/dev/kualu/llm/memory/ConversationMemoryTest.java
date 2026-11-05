package dev.kualu.llm.memory;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ToolCall;
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
    void context_withoutSummary_isTheFullHistory() {
        ConversationMemory memory = new ConversationMemory()
                .addSystem("be brief")
                .addUser("hi")
                .addAssistant("hello");

        assertEquals(memory.messages(), memory.context());
    }

    @Test
    void context_skipsTurnsFoldedIntoTheSummary() {
        ConversationMemory memory = new ConversationMemory()
                .addSystem("be brief")
                .addUser("old question")
                .addAssistant("old answer")
                .addSystem(MemoryCompressor.SUMMARY_PREFIX + "old exchange")
                .addUser("new question")
                .addAssistant("new answer");

        List<ChatMessage> context = memory.context();
        assertEquals(4, context.size());
        assertEquals("be brief", context.get(0).content());
        assertTrue(context.get(1).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertEquals("new question", context.get(2).content());
        assertEquals("new answer", context.get(3).content());

        // the folded turns stay in the full history
        assertEquals(6, memory.messages().size());
        assertEquals("old question", memory.messages().get(1).content());
    }

    @Test
    void withUser_buildsOnTheContextView() {
        ConversationMemory memory = new ConversationMemory()
                .addUser("old question")
                .addAssistant("old answer")
                .addSystem(MemoryCompressor.SUMMARY_PREFIX + "old exchange")
                .addUser("new question")
                .addAssistant("new answer");

        List<ChatMessage> payload = memory.withUser("next");
        assertEquals(4, payload.size());
        assertTrue(payload.get(0).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertEquals("new question", payload.get(1).content());
        assertEquals("new answer", payload.get(2).content());
        assertEquals("next", payload.get(3).content());
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
    void chatMessage_images_validation() {
        List<String> images = List.of("data:image/png;base64,AAAA");
        assertEquals(images, ChatMessage.user("x", images).images());
        assertEquals(null, ChatMessage.user("x").images());
        assertEquals(null, ChatMessage.user("x", List.of()).images());
        assertThrows(IllegalArgumentException.class,
                () -> new ChatMessage("user", "x", null, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatMessage("assistant", "x", null, null, images));
        assertThrows(IllegalArgumentException.class,
                () -> ChatMessage.user("x", java.util.Arrays.asList((String) null)));
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.user("x", List.of(" ")));
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

    @Test
    void assistantToolCalls_carriesTheCallList() {
        List<ToolCall> calls = List.of(new ToolCall("call_1", "exec", "{\"command\":\"ls\"}"));
        ChatMessage message = ChatMessage.assistantToolCalls("", calls);

        assertEquals("assistant", message.role());
        assertEquals("", message.content());
        assertEquals(null, message.toolCallId());
        assertEquals(1, message.toolCalls().size());
        assertEquals("call_1", message.toolCalls().get(0).id());
    }

    @Test
    void assistantToolCalls_validatesShape() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatMessage.assistantToolCalls("x", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatMessage("tool", "x", "call_1", List.of(new ToolCall("c", "exec", "{}"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatMessage("assistant", "x", "call_1", List.of(new ToolCall("c", "exec", "{}"))));
    }

    @Test
    void replaceAll_replacesStoredTurns() {
        ConversationMemory memory = new ConversationMemory()
                .addUser("old")
                .addAssistant("old reply");

        memory.replaceAll(List.of(
                ChatMessage.system("be brief"),
                ChatMessage.user("kept"),
                ChatMessage.assistant("kept reply")));

        List<ChatMessage> messages = memory.messages();
        assertEquals(3, memory.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("kept", messages.get(1).content());
        assertEquals("kept reply", messages.get(2).content());
    }

    @Test
    void replaceAll_defensivelyCopiesInput() {
        ConversationMemory memory = new ConversationMemory().addUser("hi");

        List<ChatMessage> replacement = new java.util.ArrayList<>();
        replacement.add(ChatMessage.user("first"));
        memory.replaceAll(replacement);
        replacement.add(ChatMessage.user("mutated after replace"));

        assertEquals(1, memory.size());
        assertEquals("first", memory.messages().get(0).content());
    }

    @Test
    void replaceAll_acceptsEmptyList() {
        ConversationMemory memory = new ConversationMemory()
                .addUser("hi")
                .addAssistant("hello");

        memory.replaceAll(List.of());

        assertEquals(0, memory.size());
        assertTrue(memory.messages().isEmpty());
    }

    @Test
    void replaceAll_rejectsNull() {
        ConversationMemory memory = new ConversationMemory().addUser("hi");

        assertThrows(NullPointerException.class, () -> memory.replaceAll(null));
        assertThrows(NullPointerException.class,
                () -> memory.replaceAll(java.util.Arrays.asList(ChatMessage.user("a"), null)));
        assertEquals(1, memory.size());
    }
}
