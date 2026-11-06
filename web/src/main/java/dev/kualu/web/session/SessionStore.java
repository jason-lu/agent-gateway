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

    List<SessionSummary> listByOwner(String owner);

    boolean delete(String owner, String sessionId);

    Session update(Session session);

    /**
     * Content of an externally stored image (a {@code data:} URI). Stores that
     * keep images inline in the message payload (memory store) never externalise
     * and return empty. Callers must have authorised the session first.
     */
    default Optional<String> findImageContent(String sessionId, String imageId) {
        return Optional.empty();
    }
}
