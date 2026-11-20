package dev.kualu.web.auth;

import java.util.Optional;

/** Creating accounts for invite-based registration. */
public interface UserAccountStore {

    boolean exists(String username);

    /**
     * Creates the account with the {@code USER} role; the password must already be
     * encoded. {@code email} is the invite-code address bound to the account (null
     * for accounts created outside registration).
     */
    void create(String username, String encodedPassword, String email);

    /** The address bound to the account; empty when there is none. */
    Optional<String> findEmail(String username);
}
