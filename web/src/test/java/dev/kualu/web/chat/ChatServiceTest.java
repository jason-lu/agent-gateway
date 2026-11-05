package dev.kualu.web.chat;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.web.config.LlmProperties;
import dev.kualu.web.session.InMemorySessionStore;
import dev.kualu.web.session.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        public String chat(List<ChatMessage> messages) {
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

        ChatService service = new ChatService(store, emptyProvider(), emptyCompressorProvider(), configuredProperties(), List.of(), emptyMcpProvider());
        var history = service.history(session);

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void chatForwardsImagesToTheModelAndHistoryReturnsThem() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store, clientProvider(llm), emptyCompressorProvider(), configuredProperties(), List.of(), emptyMcpProvider());
        List<String> images = List.of("data:image/png;base64,AAAA");

        assertEquals("REPLY", service.chat(session, "这是什么？", images));

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
                store, emptyProvider(), emptyCompressorProvider(), new LlmProperties(null, null, null, 90), List.of(), emptyMcpProvider());

        assertThrows(LlmNotConfiguredException.class, () -> service.chat(session, "hi", null));
        assertEquals(0, session.messageCount());
    }

    @Test
    void isLlmConfiguredRequiresPropertiesAndClient() {
        ChatService missingClient = new ChatService(
                new InMemorySessionStore(), emptyProvider(), emptyCompressorProvider(), configuredProperties(), List.of(), emptyMcpProvider());
        ChatService missingProps = new ChatService(
                new InMemorySessionStore(),
                clientProvider(new LlmClient(new LlmConfig("http://x", "k", "m"))),
                emptyCompressorProvider(),
                new LlmProperties(null, null, null, 90), List.of(), emptyMcpProvider());

        assertFalse(missingClient.isLlmConfigured());
        assertFalse(missingProps.isLlmConfigured());
    }

    @Test
    void chatCompressesBeforeBuildingThePayload() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", "sys");
        for (int i = 0; i < 10; i++) {
            session.memory().addUser("u" + i);
            session.memory().addAssistant("a" + i);
        }
        int uncompressedSize = session.messageCount();

        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store,
                clientProvider(llm),
                compressorProvider(new MemoryCompressor(llm, 8, 3, null)),
                configuredProperties(), List.of(), emptyMcpProvider());

        assertEquals("REPLY", service.chat(session, "new question", null));

        List<ChatMessage> payload = llm.replyPayloads.get(0);
        assertTrue(payload.stream().anyMatch(m -> m.content().startsWith(MemoryCompressor.SUMMARY_PREFIX)),
                "payload must already carry the summary, i.e. compression ran first");
        assertEquals("new question", payload.get(payload.size() - 1).content());
        assertTrue(payload.size() < uncompressedSize + 1, "payload must be smaller than the uncompressed history");

        // The visible history keeps every turn; only the payload was compressed.
        assertTrue(session.memory().messages().stream().anyMatch(m -> "u0".equals(m.content())),
                "folded turns must stay in the history");
        assertTrue(session.memory().messages().stream().anyMatch(m -> "a9".equals(m.content())),
                "folded turns must stay in the history");
        assertEquals(22, service.history(session).size(),
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
                clientProvider(llm),
                compressorProvider(new MemoryCompressor(llm, 8, 3, null)),
                configuredProperties(), List.of(), emptyMcpProvider());

        assertEquals("REPLY", service.chat(session, "new question", null));

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
                store, clientProvider(llm), emptyCompressorProvider(), configuredProperties(), List.of(), emptyMcpProvider());

        assertEquals("REPLY", service.chat(session, "new question", null));
        assertEquals(before + 2, session.messageCount());
        assertEquals(before + 1, llm.replyPayloads.get(0).size());
    }

    @Test
    void persistFailure_doesNotCostTheUserTheReply() {
        InMemorySessionStore store = new InMemorySessionStore() {
            @Override
            public Session update(Session session) {
                throw new org.springframework.dao.DataAccessResourceFailureException("database gone");
            }
        };
        Session session = store.create("ada", null);
        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                store, clientProvider(llm), emptyCompressorProvider(), configuredProperties(), List.of(), emptyMcpProvider());

        // The reply is already produced; a failed write must not turn it into an error.
        assertEquals("REPLY", service.chat(session, "new question", null));
        assertEquals(2, session.messageCount());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<dev.kualu.mcp.McpConnectionManager> emptyMcpProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LlmClient> emptyProvider() {
        return clientProvider(null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LlmClient> clientProvider(LlmClient client) {
        ObjectProvider<LlmClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
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

    private static LlmProperties configuredProperties() {
        return new LlmProperties("http://localhost", "key", "model", 90);
    }
}
