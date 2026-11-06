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

    /**
     * Persists the messages the store has not seen yet (the turn's fast path —
     * appends rows, never rewrites older ones) and the title if not yet stored.
     * Self-healing: covers whatever suffix is missing, so a retry after a failed
     * persist picks up where the store left off. A no-op for a deleted session.
     */
    void appendMessages(Session session);

    /**
     * Rewrites the whole message list, replacing stored rows. Required when
     * history was reshuffled in the middle (memory compression moves the fold
     * marker), which invalidates positional appends.
     */
    void replaceMessages(Session session);

    /**
     * Content of an externally stored image (a {@code data:} URI). Stores that
     * keep images inline in the message payload (memory store) never externalise
     * and return empty. Callers must have authorised the session first.
     */
    default Optional<String> findImageContent(String sessionId, String imageId) {
        return Optional.empty();
    }
}
