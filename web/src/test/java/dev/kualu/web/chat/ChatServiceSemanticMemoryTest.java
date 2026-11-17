package dev.kualu.web.chat;

import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ToolSpec;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.web.llm.LlmSettingsService;
import dev.kualu.web.memory.InMemoryMemoryStore;
import dev.kualu.web.memory.MemoryStore;
import dev.kualu.web.memory.SemanticMemory;
import dev.kualu.web.metrics.TurnMetrics;
import dev.kualu.web.session.InMemorySessionStore;
import dev.kualu.web.session.Session;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Semantic-memory wiring through {@link ChatService}: the {@code 【用户记忆】}
 * block must reach the model payload and never the stored history, and the
 * memory tools must ride along with the per-turn tool list.
 */
class ChatServiceSemanticMemoryTest {

    private MemoryStore store;
    private SemanticMemory semanticMemory;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        semanticMemory = new SemanticMemory(store, 8, true);
    }

    @Test
    void chat_injectsBlockIntoPayloadButNotHistory() {
        store.saveEntry(new MemoryEntry(null, "ada", MemoryEntry.Kind.FACT,
                "服务部署在 K8s", MemoryEntry.Source.EXPLICIT, null, null, null));
        InMemorySessionStore sessions = new InMemorySessionStore();
        Session session = sessions.create("ada", "sys");
        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                sessions, llmSettings(llm), emptyCompressorProvider(), List.of(),
                emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), disabledModeration(),
                Runnable::run, semanticMemory);

        service.chat(session, "帮我写脚本", null, null, null);

        List<ChatMessage> payload = llm.payloads.getFirst();
        assertTrue(payload.stream().anyMatch(message ->
                        "system".equals(message.role()) && message.content().contains("【用户记忆】")),
                "the memory block reaches the model");
        assertTrue(payload.stream().anyMatch(message ->
                        "system".equals(message.role()) && message.content().contains("K8s")),
                "the stored fact reaches the model");
        assertTrue(session.memory().messages().stream()
                        .noneMatch(message -> message.content().contains("【用户记忆】")),
                "the memory block never enters the history");
    }

    @Test
    void chatService_offersMemoryTools() {
        store.saveEntry(new MemoryEntry(null, "ada", MemoryEntry.Kind.FACT,
                "服务部署在 K8s", MemoryEntry.Source.EXPLICIT, null, null, null));
        InMemorySessionStore sessions = new InMemorySessionStore();
        Session session = sessions.create("ada", null);
        RecordingLlm llm = new RecordingLlm();
        ChatService service = new ChatService(
                sessions, llmSettings(llm), emptyCompressorProvider(), List.of(),
                emptyMcpProvider(), emptyUserMcpProvider(), testMetrics(), disabledModeration(),
                Runnable::run, semanticMemory);

        service.chat(session, "记住：报告用中文", null, null, null);

        assertTrue(llm.tools.stream().flatMap(List::stream)
                        .anyMatch(spec -> "memory_save".equals(spec.name())),
                "memory tools ride along with the static tools");
    }

    /** Records payloads and tool specs; answers with plain text. */
    static final class RecordingLlm extends LlmClient {
        final List<List<ChatMessage>> payloads = new ArrayList<>();
        final List<List<ToolSpec>> tools = new ArrayList<>();

        RecordingLlm() {
            super(new LlmConfig("http://localhost", "key", "model"));
        }

        @Override
        public String chat(List<ChatMessage> messages, Integer maxTokens) {
            payloads.add(List.copyOf(messages));
            return "REPLY";
        }

        @Override
        public String chatStream(List<ChatMessage> messages, java.util.function.Consumer<String> onDelta) {
            return chat(messages);
        }

        @Override
        public AssistantTurn complete(List<ChatMessage> messages, List<ToolSpec> toolSpecs) {
            payloads.add(List.copyOf(messages));
            tools.add(List.copyOf(toolSpecs));
            return new AssistantTurn("REPLY", List.of());
        }

        @Override
        public AssistantTurn completeStream(List<ChatMessage> messages, List<ToolSpec> toolSpecs,
                java.util.function.Consumer<String> onDelta) {
            return complete(messages, toolSpecs);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<dev.kualu.mcp.McpConnectionManager> emptyMcpProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<dev.kualu.web.mcp.UserMcpRegistry> emptyUserMcpProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MemoryCompressor> emptyCompressorProvider() {
        ObjectProvider<MemoryCompressor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static LlmSettingsService llmSettings(LlmClient client) {
        LlmSettingsService settings = mock(LlmSettingsService.class);
        when(settings.clientFor(any(), any(), any())).thenReturn(client);
        when(settings.isConfigured()).thenReturn(client != null);
        return settings;
    }

    private static TurnMetrics testMetrics() {
        return new TurnMetrics(new SimpleMeterRegistry());
    }

    private static dev.kualu.web.moderation.ModerationService disabledModeration() {
        return new dev.kualu.web.moderation.ModerationService(
                new dev.kualu.web.moderation.ModerationProperties(false, null, null, null),
                new dev.kualu.web.moderation.InMemoryModerationEventStore(),
                llmSettings(null));
    }
}
