package dev.kualu.web.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Authenticates against {@code agent.users} instead of the config file.
 * The {@code roles} column is a {@code text[]} of bare role names
 * ({@code USER}, {@code ADMIN}) — no {@code ROLE_} prefix, matching the config format.
 */
public class JdbcUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbc;

    public JdbcUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var rows = jdbc.query(
                "select username, password_hash, roles from agent.users where username = ?",
                (rs, rowNum) -> {
                    String[] roles = (String[]) rs.getArray("roles").getArray();
                    return User.builder()
                            .username(rs.getString("username"))
                            .password(rs.getString("password_hash"))
                            .roles(roles)
                            .build();
                },
                username);
        if (rows.isEmpty()) {
            throw new UsernameNotFoundException(username);
        }
        return rows.get(0);
    }
}
