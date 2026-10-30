package dev.kualu.web.session;

import java.util.List;
import java.util.Optional;

/**
 * Pluggable persistence for conversation sessions.
 * Implementations must isolate sessions by {@code owner}.
 */
public interface SessionStore {

    Session create(String owner, String systemPrompt);

    Optional<Session> find(String owner, String sessionId);

    List<Session> listByOwner(String owner);

    boolean delete(String owner, String sessionId);

    Session update(Session session);
}
