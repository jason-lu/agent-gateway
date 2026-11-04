package dev.kualu.web.session;

import dev.kualu.llm.memory.ConversationMemory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySessionStoreTest {

    private final InMemorySessionStore store = new InMemorySessionStore();

    @Test
    void createAddsSystemPromptAndOwner() {
        Session session = store.create("ada", "Be brief.");

        assertEquals("ada", session.owner());
        assertEquals(1, session.messageCount());
        assertTrue(session.memory().messages().stream()
                .anyMatch(message -> "system".equals(message.role()) && "Be brief.".equals(message.content())));
    }

    @Test
    void findIsOwnerScoped() {
        Session session = store.create("ada", null);

        assertTrue(store.find("ada", session.id()).isPresent());
        assertTrue(store.find("bob", session.id()).isEmpty());
    }

    @Test
    void listOnlyReturnsOwnerSessions() {
        store.create("ada", null);
        store.create("ada", null);
        store.create("bob", null);

        assertEquals(2, store.listByOwner("ada").size());
        assertEquals(1, store.listByOwner("bob").size());
    }

    @Test
    void deleteIsOwnerScoped() {
        Session session = store.create("ada", null);

        assertFalse(store.delete("bob", session.id()));
        assertTrue(store.delete("ada", session.id()));
        assertTrue(store.find("ada", session.id()).isEmpty());
    }

    @Test
    void updatePersistsMemoryMutations() {
        Session session = store.create("ada", null);
        session.memory().addUser("hi");
        session.memory().addAssistant("hello");

        Session updated = store.update(session);
        assertEquals(2, updated.messageCount());
        assertEquals(2, store.find("ada", session.id()).orElseThrow().messageCount());
    }

    @Test
    void createUsesUniqueIds() {
        Session first = store.create("ada", null);
        Session second = new Session(UUID.randomUUID().toString(), "ada", Instant.now(), new ConversationMemory());

        assertFalse(first.id().equals(second.id()));
    }
}
