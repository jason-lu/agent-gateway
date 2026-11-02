package dev.kualu.web.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresConfigTest {

    private static final String URL = "jdbc:postgresql://db.example:5432/postgres?sslmode=require";

    @Test
    void missingUrl_failsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PostgresConfig().dataSource(new DbProperties("", "postgres", "pw")));
        assertTrue(ex.getMessage().contains("agent.db.url"));
    }

    @Test
    void missingPassword_failsStartupAndMentionsTheEnvVar() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PostgresConfig().dataSource(new DbProperties(URL, "postgres", "")));
        assertTrue(ex.getMessage().contains("AGENT_DB_PASSWORD"));
    }

    @Test
    void missingSslMode_failsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PostgresConfig().dataSource(
                        new DbProperties("jdbc:postgresql://db.example:5432/postgres", "postgres", "pw")));
        assertTrue(ex.getMessage().contains("sslmode"));
    }

    @Test
    void sslModeDisable_failsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PostgresConfig().dataSource(new DbProperties(
                        "jdbc:postgresql://db.example:5432/postgres?sslmode=disable", "postgres", "pw")));
        assertTrue(ex.getMessage().contains("sslmode"));
    }

    @Test
    void sslModeDisable_isAllowedOnLoopbackOnly() {
        // Plain text cannot leave the machine over loopback, so a local/embedded
        // database stays usable. Any other host must not fall back to plaintext.
        assertNotNull(new PostgresConfig().dataSource(new DbProperties(
                "jdbc:postgresql://127.0.0.1:5432/postgres?sslmode=disable", "postgres", "pw")));
        assertNotNull(new PostgresConfig().dataSource(new DbProperties(
                "jdbc:postgresql://localhost:5432/postgres?sslmode=disable", "postgres", "pw")));

        assertThrows(IllegalStateException.class, () -> new PostgresConfig().dataSource(new DbProperties(
                "jdbc:postgresql://db.example:5432/postgres?sslmode=disable", "postgres", "pw")));
        assertThrows(IllegalStateException.class, () -> new PostgresConfig().dataSource(new DbProperties(
                "jdbc:postgresql://10.0.0.5:5432/postgres?sslmode=disable", "postgres", "pw")));
    }

    /**
     * {@code prefer} and {@code allow} negotiate TLS but fall back to plaintext, so a
     * deny-list that only rejects {@code disable} would let credentials cross the
     * network unencrypted.
     */
    @Test
    void sslModeThatCanFallBackToPlaintext_failsStartup() {
        for (String mode : new String[] {"prefer", "allow", "unknown"}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new PostgresConfig().dataSource(new DbProperties(
                            "jdbc:postgresql://db.example:5432/postgres?sslmode=" + mode, "postgres", "pw")),
                    "sslmode=" + mode + " must be rejected");
            assertTrue(ex.getMessage().contains("sslmode"), ex.getMessage());
        }
    }

    @Test
    void strictSslModes_areAccepted() {
        for (String mode : new String[] {"require", "verify-ca", "verify-full"}) {
            assertNotNull(new PostgresConfig().dataSource(new DbProperties(
                    "jdbc:postgresql://db.example:5432/postgres?sslmode=" + mode, "postgres", "pw")),
                    "sslmode=" + mode + " must be accepted");
        }
    }

    @Test
    void missingUsername_failsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PostgresConfig().dataSource(new DbProperties(URL, "", "pw")));
        assertTrue(ex.getMessage().contains("agent.db.username"));
    }

    @Test
    void validConfig_buildsDataSource() {
        var dataSource = new PostgresConfig().dataSource(new DbProperties(URL, "postgres", "pw"));

        assertNotNull(dataSource);
        assertEquals(URL, ((com.zaxxer.hikari.HikariDataSource) dataSource).getJdbcUrl());
        assertEquals("postgres", ((com.zaxxer.hikari.HikariDataSource) dataSource).getUsername());
    }
}
