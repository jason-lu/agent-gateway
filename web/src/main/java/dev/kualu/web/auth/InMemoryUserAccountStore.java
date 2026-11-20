package dev.kualu.web.auth;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.User;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers into the config-backed user manager. Registrations live until
 * restart — the manager is rebuilt from {@code agent.security.users} on boot.
 * Bound addresses are kept beside it: {@code UserDetails} has no email slot.
 */
public class InMemoryUserAccountStore implements UserAccountStore {

    private final InMemoryUserDetailsManager users;
    private final ConcurrentHashMap<String, String> emails = new ConcurrentHashMap<>();

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
    public void create(String username, String encodedPassword, String email) {
        users.createUser(User.builder()
                .username(username)
                .password(encodedPassword)
                .roles("USER")
                .build());
        if (email != null && !email.isBlank()) {
            emails.put(username, email.trim());
        }
    }

    @Override
    public Optional<String> findEmail(String username) {
        return Optional.ofNullable(emails.get(username));
    }
}
