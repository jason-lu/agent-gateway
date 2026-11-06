package dev.kualu.web.chat;

import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.web.config.LlmProperties;
import dev.kualu.web.metrics.TurnMetrics;
import dev.kualu.web.session.InMemorySessionStore;
import dev.kualu.web.session.Session;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceConcurrencyTest {

    private static TurnMetrics testMetrics() {
        return new TurnMetrics(new SimpleMeterRegistry());
    }

    @Test
    void parallelChatsOnOneSessionDoNotCorruptHistory() throws Exception {
        LlmClient client = mock(LlmClient.class);
        when(client.chat(anyList())).thenAnswer(invocation -> {
            Thread.sleep(5);
            return "ok";
        });

        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(
                store,
                provider(client),
                emptyCompressorProvider(),
                new LlmProperties("http://localhost", "key", "model", 90), List.of(), emptyMcpProvider(), testMetrics(), Runnable::run);

        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    service.chat(session, "hello", null);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(workers * 2, session.messageCount());
        assertEquals(workers * 2, store.find("ada", session.id()).orElseThrow().messageCount());
    }

    @Test
    void updateDoesNotResurrectDeletedSession() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        assertTrue(store.delete("ada", session.id()));
        store.appendMessages(session);
        assertTrue(store.find("ada", session.id()).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<LlmClient> provider(LlmClient client) {
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<dev.kualu.llm.memory.MemoryCompressor> emptyCompressorProvider() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<dev.kualu.mcp.McpConnectionManager> emptyMcpProvider() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }
}
