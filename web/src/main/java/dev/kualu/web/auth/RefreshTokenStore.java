package dev.kualu.web.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * Server-side record of an interactive login session: one row per login (or
 * browser), referenced from the access JWT via its {@code sid} claim and from
 * the refresh token via a SHA-256 hash of the raw value. Backed by PostgreSQL
 * ({@code agent.refresh_tokens}) or by process memory ({@code agent.store=memory}).
 */
public interface RefreshTokenStore {

    record Entry(String id, String owner, Instant createdAt, Instant lastUsedAt) {
    }

    void insert(Entry entry, String tokenHash);

    Optional<Entry> findByHash(String tokenHash);

    Optional<Entry> findById(String id);

    /** Updates the refresh-token hash and the activity timestamp (rotation). */
    boolean rotate(String id, String newTokenHash, Instant usedAt);

    /** Marks activity without rotating. Returns {@code false} if the row is gone. */
    boolean touch(String id, Instant usedAt);

    void deleteById(String id);
}
