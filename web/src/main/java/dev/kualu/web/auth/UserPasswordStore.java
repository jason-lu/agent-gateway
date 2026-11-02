package dev.kualu.web.auth;

/**
 * Replaces the stored password hash for an account. Backed by the config-seeded
 * in-memory users ({@code agent.store=memory}) or by {@code agent.users}
 * ({@code agent.store=postgres}).
 */
public interface UserPasswordStore {

    void updatePassword(String username, String encodedPassword);
}
