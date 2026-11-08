package dev.kualu.web.chat;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.web.llm.LlmSettingsService;
import dev.kualu.web.metrics.TurnMetrics;
import dev.kualu.web.session.InMemorySessionStore;
import dev.kualu.web.session.Session;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceStreamTest {

    private static TurnMetrics testMetrics() {
        return new TurnMetrics(new SimpleMeterRegistry());
    }

    @Test
    void chatStreamCollectsDeltasAndPersistsTurns() {
        LlmClient client = mock(LlmClient.class);
        when(client.chatStream(anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var onDelta = (java.util.function.Consumer<String>) invocation.getArgument(1);
            onDelta.accept("Hel");
            onDelta.accept("lo");
            return "Hello";
        });

        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store,
                llmSettings(client),
                emptyCompressorProvider(),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        List<String> deltas = new ArrayList<>();
        String reply = service.chatStream(session, "hi", null, deltas::add);

        assertEquals("Hello", reply);
        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals(2, session.messageCount());
        assertEquals(2, store.find("ada", session.id()).orElseThrow().messageCount());
    }

    @Test
    void failedChatDoesNotPersistTurns() {
        LlmClient client = mock(LlmClient.class);
        when(client.chat(anyList())).thenThrow(new dev.kualu.llm.LlmException("boom", 500, "err"));

        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store,
                llmSettings(client),
                emptyCompressorProvider(),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        try {
            service.chat(session, "hi", null);
        } catch (UpstreamLlmException expected) {
            assertTrue(expected.getMessage().contains("boom"));
        }
        assertEquals(0, session.messageCount());
    }

    @Test
    void partialReplyIsKeptWhenSendFailsMidStream() {
        LlmClient client = mock(LlmClient.class);
        when(client.chatStream(anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var onDelta = (java.util.function.Consumer<String>) invocation.getArgument(1);
            onDelta.accept("Hel");
            onDelta.accept("lo");
            return "Hello";
        });

        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store,
                llmSettings(client),
                emptyCompressorProvider(),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        List<String> deltas = new ArrayList<>();
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.chatStream(session, "hi", null, delta -> {
                    deltas.add(delta);
                    if (delta.equals("lo")) {
                        throw new IllegalStateException("SSE send failed");
                    }
                }));
        assertEquals("SSE send failed", thrown.getMessage());

        // The turn survives with the reply produced up to the cut, in the store too.
        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals(2, session.messageCount());
        Session persisted = store.find("ada", session.id()).orElseThrow();
        assertEquals(2, persisted.messageCount());
        assertEquals("hi", persisted.memory().messages().get(0).content());
        assertEquals("Hello", persisted.memory().messages().get(1).content());
    }

    private static LlmSettingsService llmSettings(LlmClient client) {
        LlmSettingsService settings = mock(LlmSettingsService.class);
        when(settings.client()).thenReturn(client);
        when(settings.isConfigured()).thenReturn(true);
        return settings;
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<dev.kualu.llm.memory.MemoryCompressor> emptyCompressorProvider() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<dev.kualu.mcp.McpConnectionManager> emptyMcpProvider() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<dev.kualu.web.mcp.UserMcpRegistry> emptyUserMcpProvider() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }
}
