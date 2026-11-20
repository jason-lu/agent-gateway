package dev.kualu.web.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * Single-use password-reset tokens: the raw value only ever travels by email,
 * the store keeps its SHA-256 hash. Backed by PostgreSQL
 * ({@code agent.password_reset_tokens}) or by process memory.
 */
public interface PasswordResetTokenStore {

    record Token(String tokenHash, String username, Instant createdAt, Instant expiresAt, Instant usedAt) {

        public boolean used() {
            return usedAt != null;
        }

        public boolean expired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    void insert(Token token);

    Optional<Token> find(String tokenHash);

    /** Marks the token spent. Returns {@code false} when it is already spent. */
    boolean markUsed(String tokenHash, Instant at);

    /** Drops every outstanding token for the account (a new request supersedes old ones). */
    void deleteAllFor(String username);
}
