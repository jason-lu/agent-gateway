package dev.kualu.web.auth;

import dev.kualu.web.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcUserSeederTest {

    private record Result(String sql, PreparedStatement ps, Connection con) {}

    /** Runs the seeder and returns the prepared statement it actually built. */
    private Result seed(PasswordEncoder encoder, SecurityProperties.User user, int affectedRows) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(PreparedStatementCreator.class))).thenReturn(affectedRows);

        PreparedStatement ps = mock(PreparedStatement.class);
        Connection con = mock(Connection.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(con.prepareStatement(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return ps;
        });
        when(con.createArrayOf(anyString(), any())).thenReturn(mock(Array.class));

        int created = new JdbcUserSeeder(jdbc, encoder).seed(List.of(user));
        assertEquals(affectedRows, created);

        ArgumentCaptor<PreparedStatementCreator> captor = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc).update(captor.capture());
        captor.getValue().createPreparedStatement(con);

        return new Result(sql.get(), ps, con);
    }

    @Test
    void seed_insertsIfAbsent() throws Exception {
        Result result = seed(mock(PasswordEncoder.class),
                new SecurityProperties.User("alice", "secret", List.of("USER")), 1);

        assertTrue(result.sql().contains("on conflict (username) do nothing"),
                "config users must never overwrite an account edited in the database: " + result.sql());
    }

    @Test
    void seed_storesTheEncodedPasswordNotThePlaintext() throws Exception {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("secret")).thenReturn("$2a$encoded");

        Result result = seed(encoder, new SecurityProperties.User("alice", "secret", List.of("USER")), 1);

        verify(encoder).encode("secret");
        verify(result.ps()).setString(1, "alice");
        verify(result.ps()).setString(2, "$2a$encoded");
    }

    @Test
    void seed_writesRolesAsATextArray() throws Exception {
        Result result = seed(mock(PasswordEncoder.class),
                new SecurityProperties.User("alice", "p", List.of("USER", "ADMIN")), 1);

        verify(result.con()).createArrayOf("text", new Object[] {"USER", "ADMIN"});
    }

    @Test
    void seed_skipsExistingAccounts() throws Exception {
        // "on conflict do nothing" reports 0 affected rows when the account already exists;
        // the helper asserts the seeder returns that count unchanged.
        seed(mock(PasswordEncoder.class),
                new SecurityProperties.User("alice", "secret", List.of("USER")), 0);
    }
}
