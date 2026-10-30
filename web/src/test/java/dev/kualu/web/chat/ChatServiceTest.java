package dev.kualu.web.chat;

import dev.kualu.llm.LlmClient;
import dev.kualu.llm.LlmConfig;
import dev.kualu.web.config.LlmProperties;
import dev.kualu.web.session.InMemorySessionStore;
import dev.kualu.web.session.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    @Test
    void historyFiltersToUserAssistantTurns() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", "sys");
        session.memory().addUser("hello");
        session.memory().addAssistant("hi");
        session.memory().addTool("tool", "call-1");

        ChatService service = new ChatService(store, emptyProvider(), configuredProperties());
        var history = service.history(session);

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void chatThrowsWhenLlmMissing() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = store.create("ada", null);
        ChatService service = new ChatService(store, emptyProvider(), new LlmProperties(null, null, null));

        assertThrows(LlmNotConfiguredException.class, () -> service.chat(session, "hi"));
        assertEquals(0, session.messageCount());
    }

    @Test
    void isLlmConfiguredRequiresPropertiesAndClient() {
        ChatService missingClient = new ChatService(
                new InMemorySessionStore(), emptyProvider(), configuredProperties());
        ChatService missingProps = new ChatService(
                new InMemorySessionStore(),
                clientProvider(new LlmClient(new LlmConfig("http://x", "k", "m"))),
                new LlmProperties(null, null, null));

        assertFalse(missingClient.isLlmConfigured());
        assertFalse(missingProps.isLlmConfigured());
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

    private static LlmProperties configuredProperties() {
        return new LlmProperties("http://localhost", "key", "model");
    }
}
