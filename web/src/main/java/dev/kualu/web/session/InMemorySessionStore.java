package dev.kualu.web.session;

import dev.kualu.llm.memory.ConversationMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local session store. Sessions are lost on restart.
 * Only registered when {@code agent.store=memory}.
 */
@Component
@ConditionalOnProperty(name = "agent.store", havingValue = "memory")
public class InMemorySessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionStore.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public InMemorySessionStore() {
        log.warn("agent.store=memory: sessions and users are NOT persisted; "
                + "all history is lost on restart. Do not use this in production.");
    }

    @Override
    public Session create(String owner, String systemPrompt) {
        ConversationMemory memory = new ConversationMemory();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            memory.addSystem(systemPrompt);
        }
        Session session = new Session(UUID.randomUUID().toString(), owner, Instant.now(), memory);
        sessions.put(session.id(), session);
        return session;
    }

    @Override
    public Optional<Session> find(String owner, String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null || !session.owner().equals(owner)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    @Override
    public List<SessionSummary> listByOwner(String owner) {
        return sessions.values().stream()
                .filter(session -> session.owner().equals(owner))
                .sorted(Comparator.comparing(Session::createdAt).reversed())
                .map(session -> new SessionSummary(
                        session.id(), session.owner(), session.createdAt(), session.title(),
                        session.messageCount()))
                .toList();
    }

    @Override
    public boolean delete(String owner, String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null || !session.owner().equals(owner)) {
            return false;
        }
        return sessions.remove(sessionId, session);
    }

    @Override
    public void appendMessages(Session session) {
        // The in-memory session *is* the payload; nothing to flush. Never
        // resurrect a session deleted while a chat turn was in flight.
    }

    @Override
    public void replaceMessages(Session session) {
        // Same as appendMessages: history lives in the session object itself.
    }
}
