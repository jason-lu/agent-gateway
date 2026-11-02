package dev.kualu.web.auth;

import dev.kualu.web.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * Bootstrap: inserts the config-declared users on first start and never overwrites
 * an existing row. After the first run the database is the source of truth — a
 * password or role changed by hand survives restarts, which is why this is
 * insert-if-absent rather than an upsert.
 */
public class JdbcUserSeeder {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserSeeder.class);

    /** Shipped placeholder in {@code application.yml}; fine in a sandbox, a hole in a real database. */
    static final String SHIPPED_PLACEHOLDER_PASSWORD = "change-me";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public JdbcUserSeeder(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /** @return how many accounts were actually created */
    public int seed(List<SecurityProperties.User> users) {
        int created = 0;
        for (SecurityProperties.User user : users) {
            warnIfPlaceholder(user);
            int inserted = jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "insert into agent.users (username, password_hash, roles) values (?, ?, ?) "
                                + "on conflict (username) do nothing");
                ps.setString(1, user.username());
                ps.setString(2, encoder.encode(user.password()));
                ps.setArray(3, con.createArrayOf("text", user.roles().toArray()));
                return ps;
            });
            created += inserted;
        }
        if (created > 0) {
            log.info("user-seed: created {} account(s) from agent.security.users", created);
        }
        return created;
    }

    private static void warnIfPlaceholder(SecurityProperties.User user) {
        if (SHIPPED_PLACEHOLDER_PASSWORD.equals(user.password())) {
            log.warn("user-seed: account '{}' is about to be created with the SHIPPED PLACEHOLDER password; "
                    + "set AGENT_ADMIN_PASSWORD (or agent.security.users[].password) before exposing this service",
                    user.username());
        }
    }
}
