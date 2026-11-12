package dev.kualu.web.auth;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.User;

/**
 * Registers into the config-backed user manager. Registrations live until
 * restart — the manager is rebuilt from {@code agent.security.users} on boot.
 */
public class InMemoryUserAccountStore implements UserAccountStore {

    private final InMemoryUserDetailsManager users;

    public InMemoryUserAccountStore(InMemoryUserDetailsManager users) {
        this.users = users;
    }

    @Override
    public boolean exists(String username) {
        try {
            users.loadUserByUsername(username);
            return true;
        } catch (UsernameNotFoundException ex) {
            return false;
        }
    }

    @Override
    public void create(String username, String encodedPassword) {
        users.createUser(User.builder()
                .username(username)
                .password(encodedPassword)
                .roles("USER")
                .build());
    }
}
