package dev.kualu.web.mcp;

import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-migration persistence for {@code agent.user_mcp_servers} (zonky embedded PostgreSQL). */
class PostgresUserMcpServerStoreTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PostgresUserMcpServerStore store;

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        store = new PostgresUserMcpServerStore(jdbc, new ObjectMapper());
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void migrateFresh() {
        jdbc.execute("drop schema if exists agent cascade");
        Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc.update("insert into agent.users (username, password_hash) values ('ada', 'x'), ('bob', 'x')");
    }

    @Test
    void roundTripsEveryField() {
        UserMcpServer server = new UserMcpServer(
                "github", UserMcpServer.TRANSPORT_HTTP, null, List.of(), Map.of(),
                "https://example.com/mcp",
                Map.of("Authorization", "Bearer t0ken+/=", "X-Env", "值 {json}"),
                false);
        store.save("ada", server);

        UserMcpServer loaded = store.find("ada", "github").orElseThrow();
        assertEquals(server, loaded);
    }

    @Test
    void roundTripsStdioArgv() {
        UserMcpServer server = new UserMcpServer(
                "playwright", UserMcpServer.TRANSPORT_STDIO, "npx", List.of("-y", "@playwright/mcp@latest"),
                Map.of("HEADLESS", "true"), null, Map.of(), true);
        store.save("ada", server);

        UserMcpServer loaded = store.find("ada", "playwright").orElseThrow();
        assertEquals(List.of("-y", "@playwright/mcp@latest"), loaded.args());
        assertEquals(Map.of("HEADLESS", "true"), loaded.env());
    }

    @Test
    void upsertReplacesAndIsolatesOwners() {
        store.save("ada", new UserMcpServer(
                "s", UserMcpServer.TRANSPORT_HTTP, null, List.of(), Map.of(),
                "https://a.example/mcp", Map.of("k", "old"), true));
        store.save("ada", new UserMcpServer(
                "s", UserMcpServer.TRANSPORT_HTTP, null, List.of(), Map.of(),
                "https://b.example/mcp", Map.of("k", "new"), true));

        assertEquals(1, store.listByOwner("ada").size());
        assertEquals("https://b.example/mcp", store.find("ada", "s").orElseThrow().url());
        assertEquals(Map.of("k", "new"), store.find("ada", "s").orElseThrow().headers());
        assertTrue(store.find("bob", "s").isEmpty());
        assertTrue(store.listByOwner("bob").isEmpty());
    }

    @Test
    void deleteIsScopedToOwner() {
        UserMcpServer server = new UserMcpServer(
                "gone", UserMcpServer.TRANSPORT_HTTP, null, List.of(), Map.of(),
                "https://example.com/mcp", Map.of(), true);
        store.save("ada", server);
        store.save("bob", server);

        assertTrue(store.delete("ada", "gone"));
        assertFalse(store.delete("ada", "gone"));
        assertTrue(store.find("ada", "gone").isEmpty());
        assertTrue(store.find("bob", "gone").isPresent());
    }
}
