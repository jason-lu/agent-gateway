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

    /** Moderation off: these tests exercise stream plumbing, not content safety. */
    private static dev.kualu.web.moderation.ModerationService disabledModeration() {
        return new dev.kualu.web.moderation.ModerationService(
                new dev.kualu.web.moderation.ModerationProperties(false, null, null, null),
                new dev.kualu.web.moderation.InMemoryModerationEventStore(),
                llmSettings(null));
    }

    /** Moderation on in gray-release (flag): audited, but nothing is intercepted. */
    private static dev.kualu.web.moderation.ModerationService flagModeration() throws Exception {
        java.nio.file.Path lexicon = java.nio.file.Files.createTempFile("lexicon", ".json");
        java.nio.file.Files.writeString(lexicon, """
                {"categories": {"ABUSE": {"severity": "LOW", "terms": [{"pattern": "脏话示例", "type": "exact"}]}}}
                """);
        return new dev.kualu.web.moderation.ModerationService(
                new dev.kualu.web.moderation.ModerationProperties(true, "flag", lexicon.toString(),
                        new dev.kualu.web.moderation.ModerationProperties.Llm(false, null, null, 0)),
                new dev.kualu.web.moderation.InMemoryModerationEventStore(),
                llmSettings(null));
    }

    @Test
    void chatStreamCollectsDeltasAndPersistsTurns() {
        LlmClient client = mock(LlmClient.class);
        when(client.completeStream(anyList(), anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var onDelta = (java.util.function.Consumer<String>) invocation.getArgument(2);
            onDelta.accept("Hel");
            onDelta.accept("lo");
            return new dev.kualu.llm.AssistantTurn("Hello", List.of());
        });

        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store,
                llmSettings(client),
                emptyCompressorProvider(),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), disabledModeration(), Runnable::run);

        List<String> deltas = new ArrayList<>();
        String reply = service.chatStream(session, "hi", null, null, null, deltas::add);

        assertEquals("Hello", reply);
        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals(2, session.messageCount());
        assertEquals(2, store.find("ada", session.id()).orElseThrow().messageCount());
    }

    @Test
    void flagModeModerationForwardsDeltasUnbatched() throws Exception {
        // Gray-release mode must not batch deltas into sentence segments: the
        // segment gate is skipped there, so each delta reaches the client as-is.
        LlmClient client = mock(LlmClient.class);
        when(client.completeStream(anyList(), anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var onDelta = (java.util.function.Consumer<String>) invocation.getArgument(2);
            onDelta.accept("第一句还没结束");
            onDelta.accept("。第二句！");
            return new dev.kualu.llm.AssistantTurn("第一句还没结束。第二句！", List.of());
        });

        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store,
                llmSettings(client),
                emptyCompressorProvider(),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), flagModeration(), Runnable::run);

        List<String> deltas = new ArrayList<>();
        service.chatStream(session, "hi", null, null, null, deltas::add);

        assertEquals(List.of("第一句还没结束", "。第二句！"), deltas);
    }

    @Test
    void failedChatDoesNotPersistTurns() {
        LlmClient client = mock(LlmClient.class);
        when(client.complete(anyList(), anyList())).thenThrow(new dev.kualu.llm.LlmException("boom", 500, "err"));

        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store,
                llmSettings(client),
                emptyCompressorProvider(),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), disabledModeration(), Runnable::run);

        try {
            service.chat(session, "hi", null, null, null);
        } catch (UpstreamLlmException expected) {
            assertTrue(expected.getMessage().contains("boom"));
        }
        assertEquals(0, session.messageCount());
    }

    @Test
    void partialReplyIsKeptWhenSendFailsMidStream() {
        LlmClient client = mock(LlmClient.class);
        when(client.completeStream(anyList(), anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var onDelta = (java.util.function.Consumer<String>) invocation.getArgument(2);
            onDelta.accept("Hel");
            onDelta.accept("lo");
            return new dev.kualu.llm.AssistantTurn("Hello", List.of());
        });

        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store,
                llmSettings(client),
                emptyCompressorProvider(),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), disabledModeration(), Runnable::run);

        List<String> deltas = new ArrayList<>();
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.chatStream(session, "hi", null, null, null, delta -> {
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
        when(settings.clientFor(any(), any(), any())).thenReturn(client);
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
