package dev.kualu.web.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registers into {@code agent.users} with the bare {@code USER} role. */
public class PostgresUserAccountStore implements UserAccountStore {

    private final JdbcTemplate jdbc;

    public PostgresUserAccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(String username) {
        return jdbc.queryForObject(
                "select exists(select 1 from agent.users where username = ?)",
                Boolean.class, username);
    }

    @Override
    public void create(String username, String encodedPassword) {
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "insert into agent.users (username, password_hash, roles) values (?, ?, ?)");
                ps.setString(1, username);
                ps.setString(2, encodedPassword);
                ps.setArray(3, con.createArrayOf("text", new String[] {"USER"}));
                return ps;
            });
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("username already taken: " + username);
        }
    }
}
