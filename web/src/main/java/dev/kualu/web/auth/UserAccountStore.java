package dev.kualu.web.auth;

/** Creating accounts for invite-based registration. */
public interface UserAccountStore {

    boolean exists(String username);

    /** Creates the account with the {@code USER} role; the password must already be encoded. */
    void create(String username, String encodedPassword);
}
