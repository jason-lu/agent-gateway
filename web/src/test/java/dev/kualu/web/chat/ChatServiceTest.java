package dev.kualu.web.chat;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.web.llm.LlmSettingsService;
import dev.kualu.web.metrics.TurnMetrics;
import dev.kualu.web.session.InMemorySessionStore;
import dev.kualu.web.session.Session;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    /**
     * Records every {@code chat} call and answers summarisation prompts
     * ({@code [Previous summary]}) differently from regular turns.
     */
    static final class RecordingLlm extends LlmClient {
        final List<List<ChatMessage>> replyPayloads = new ArrayList<>();
        RuntimeException summarizeFailure;

        RecordingLlm() {
            super(new LlmConfig("http://localhost", "key", "model"));
        }

        @Override
        public String chat(List<ChatMessage> messages, Integer maxTokens) {
            if (isSummarization(messages)) {
                if (summarizeFailure != null) {
                    throw summarizeFailure;
                }
                return "SUMMARY-TEXT";
            }
            replyPayloads.add(List.copyOf(messages));
            return "REPLY";
        }

        @Override
        public String chatStream(List<ChatMessage> messages, java.util.function.Consumer<String> onDelta) {
            return chat(messages);
        }

        private static boolean isSummarization(List<ChatMessage> messages) {
            return messages.size() == 2
                    && "system".equals(messages.get(0).role())
                    && messages.get(1).content().startsWith("[Previous summary]");
        }
    }

    @Test
    void historyFiltersToUserAssistantTurns() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", "sys");
        session.memory().addUser("hello");
        session.memory().addAssistant("hi");
        session.memory().addTool("tool", "call-1");

        ChatService service = new ChatService(store, emptyLlmSettings(), emptyCompressorProvider(), List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);
        var history = service.history(session);

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void rawHistoryKeepsEveryTurnIncludingToolTrafficAndSummaries() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        session.memory().replaceAll(List.of(
                ChatMessage.system("you are terse"),
                ChatMessage.user("hello"),
                ChatMessage.assistantToolCalls("", List.of(new ToolCall("call-1", "exec", "{\"command\":\"ls\"}"))),
                ChatMessage.tool("file1", "call-1"),
                ChatMessage.system(MemoryCompressor.SUMMARY_PREFIX + " earlier talk"),
                ChatMessage.user("and then")));

        ChatService service = new ChatService(
                store, emptyLlmSettings(), emptyCompressorProvider(), List.of(),
                emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);
        var raw = service.rawHistory(session);

        assertEquals(6, raw.size());
        assertEquals("you are terse", raw.get(0).content());
        assertEquals(null, raw.get(0).toolCalls());
        assertEquals("", raw.get(2).content());
        assertEquals(1, raw.get(2).toolCalls().size());
        assertEquals("call-1", raw.get(2).toolCalls().get(0).id());
        assertEquals("exec", raw.get(2).toolCalls().get(0).name());
        assertEquals("{\"command\":\"ls\"}", raw.get(2).toolCalls().get(0).arguments());
        assertEquals("tool", raw.get(3).role());
        assertEquals("call-1", raw.get(3).toolCallId());
        assertEquals("file1", raw.get(3).content());
        assertTrue(raw.get(4).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
        assertEquals("and then", raw.get(5).content());
    }

    @Test
    void rawHistoryKeepsStoredImageRefs() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        session.memory().replaceAll(List.of(
                ChatMessage.user("look", List.of("ref:" + session.id() + "/abc123"))));

        ChatService service = new ChatService(
                store, emptyLlmSettings(), emptyCompressorProvider(), List.of(),
                emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        assertEquals(List.of("ref:" + session.id() + "/abc123"), service.rawHistory(session).get(0).images());
    }

    @Test
    void chatForwardsImagesToTheModelAndHistoryReturnsThem() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store, llmSettings(llm), emptyCompressorProvider(), List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);
        List<String> images = List.of("data:image/png;base64,AAAA");

        assertEquals("REPLY", service.chat(session, "这是什么？", images, null, null));

        List<ChatMessage> payload = llm.replyPayloads.get(0);
        ChatMessage sentUser = payload.get(payload.size() - 1);
        assertEquals("user", sentUser.role());
        assertEquals(images, sentUser.images());

        var history = service.history(session);
        assertEquals(2, history.size());
        assertEquals(images, history.get(0).images());
        assertEquals(null, history.get(1).images());
    }

    @Test
    void chatThrowsWhenLlmMissing() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store, emptyLlmSettings(), emptyCompressorProvider(), List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        assertThrows(LlmNotConfiguredException.class, () -> service.chat(session, "hi", null, null, null));
        assertEquals(0, session.messageCount());
    }

    @Test
    void isLlmConfiguredRequiresAnEffectiveClient() {
        ChatService unconfigured = new ChatService(
                new InMemorySessionStore(), emptyLlmSettings(), emptyCompressorProvider(), List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);
        ChatService configured = new ChatService(
                new InMemorySessionStore(),
                llmSettings(new LlmClient(new LlmConfig("http://x", "k", "m"))),
                emptyCompressorProvider(),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        assertFalse(unconfigured.isLlmConfigured());
        assertTrue(configured.isLlmConfigured());
    }

    @Test
    void chatCompressesAfterTheTurn() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", "sys");
        for (int i = 0; i < 10; i++) {
            session.memory().addUser("u" + i);
            session.memory().addAssistant("a" + i);
        }

        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store,
                llmSettings(llm),
                compressorProvider(new MemoryCompressor(llm, 8, 3, null, 4000)),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        assertEquals("REPLY", service.chat(session, "new question", null, null, null));

        // The triggering turn runs with the uncompressed history — it must not
        // wait for the summariser.
        List<ChatMessage> firstPayload = llm.replyPayloads.get(0);
        assertTrue(firstPayload.stream().noneMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)),
                "the triggering turn must not carry the summary");
        assertEquals("new question", firstPayload.get(firstPayload.size() - 1).content());

        // The post-turn fold ran, so the next payload is bounded.
        assertTrue(session.memory().context().stream()
                        .anyMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)),
                "compression runs right after the turn");
        assertEquals("REPLY", service.chat(session, "follow up", null, null, null));
        List<ChatMessage> secondPayload = llm.replyPayloads.get(1);
        assertTrue(secondPayload.stream().anyMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)),
                "the next turn's payload carries the summary");
        assertTrue(secondPayload.size() < firstPayload.size(),
                "the compressed payload must be smaller");

        // The visible history keeps every turn; only the payload was compressed.
        assertTrue(session.memory().messages().stream().anyMatch(m -> "u0".equals(m.content())),
                "folded turns must stay in the history");
        assertTrue(session.memory().messages().stream().anyMatch(m -> "a9".equals(m.content())),
                "folded turns must stay in the history");
        assertEquals(24, service.history(session).size(),
                "history must show every user/assistant turn, folded or not");
    }

    @Test
    void compressionFailure_doesNotBlockTheReply() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", "sys");
        for (int i = 0; i < 10; i++) {
            session.memory().addUser("u" + i);
            session.memory().addAssistant("a" + i);
        }

        RecordingLlm llm = new RecordingLlm();
        llm.summarizeFailure = new LlmException("summariser down");
        ChatService service = new ChatService(
                store,
                llmSettings(llm),
                compressorProvider(new MemoryCompressor(llm, 8, 3, null, 4000)),
                List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        assertEquals("REPLY", service.chat(session, "new question", null, null, null));

        // History grew by the new turn and no summary was written.
        assertEquals(23, session.messageCount());
        assertTrue(session.memory().messages().stream()
                .noneMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)));
    }

    @Test
    void compressionDisabled_leavesHistoryAlone() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", "sys");
        for (int i = 0; i < 10; i++) {
            session.memory().addUser("u" + i);
            session.memory().addAssistant("a" + i);
        }
        int before = session.messageCount();

        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store, llmSettings(llm), emptyCompressorProvider(), List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        assertEquals("REPLY", service.chat(session, "new question", null, null, null));
        assertEquals(before + 2, session.messageCount());
        assertEquals(before + 1, llm.replyPayloads.get(0).size());
    }

    @Test
    void persistFailure_doesNotCostTheUserTheReply() {
        InMemorySessionStore store = new InMemorySessionStore() {
            @Override
            public void appendMessages(Session session) {
                throw new org.springframework.dao.DataAccessResourceFailureException("database gone");
            }
        };
        Session session = store.create("ada", null);
        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store, llmSettings(llm), emptyCompressorProvider(), List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        // The reply is already produced; a failed write must not turn it into an error.
        assertEquals("REPLY", service.chat(session, "new question", null, null, null));
        assertEquals(2, session.messageCount());
    }

    @Test
    void chat_derivesTheListingTitleFromTheFirstUserMessage() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store, llmSettings(llm), emptyCompressorProvider(), List.of(), emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        service.chat(session, "  帮我看看\n这个项目结构  ", null, null, null);

        assertEquals("帮我看看 这个项目结构", session.title());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<dev.kualu.mcp.McpConnectionManager> emptyMcpProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<dev.kualu.web.mcp.UserMcpRegistry> emptyUserMcpProvider() {
        return mock(ObjectProvider.class);
    }

    private static LlmSettingsService llmSettings(LlmClient client) {
        LlmSettingsService settings = mock(LlmSettingsService.class);
        when(settings.clientFor(any(), any(), any())).thenReturn(client);
        when(settings.isConfigured()).thenReturn(client != null);
        return settings;
    }

    private static LlmSettingsService emptyLlmSettings() {
        return llmSettings(null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MemoryCompressor> emptyCompressorProvider() {
        return compressorProvider(null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MemoryCompressor> compressorProvider(MemoryCompressor compressor) {
        ObjectProvider<MemoryCompressor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(compressor);
        return provider;
    }

    @Test
    void historyMapsStoredRefsToImageUrls() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        session.memory().replaceAll(List.of(
                ChatMessage.user("look",
                        List.of("ref:" + session.id() + "/abc123", "data:image/png;base64,AAAA"))));

        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store, llmSettings(llm), emptyCompressorProvider(), List.of(),
                emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), Runnable::run);

        List<String> images = service.history(session).get(0).images();
        assertEquals(List.of(
                "/api/v1/sessions/" + session.id() + "/images/abc123",
                "data:image/png;base64,AAAA"),
                images);
    }

    private static TurnMetrics testMetrics() {
        return new TurnMetrics(new SimpleMeterRegistry());
    }
}
