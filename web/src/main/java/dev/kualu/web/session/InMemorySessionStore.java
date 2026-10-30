package dev.kualu.web.session;

import dev.kualu.llm.ConversationMemory;
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
 */
@Component
public class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

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
    public List<Session> listByOwner(String owner) {
        return sessions.values().stream()
                .filter(session -> session.owner().equals(owner))
                .sorted(Comparator.comparing(Session::createdAt).reversed())
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
    public Session update(Session session) {
        // Do not resurrect a session deleted while a chat turn was in flight.
        sessions.computeIfPresent(session.id(), (id, existing) ->
                existing.owner().equals(session.owner()) ? session : existing);
        return session;
    }
}
