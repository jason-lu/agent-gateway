package dev.kualu.web.tools;

import dev.kualu.web.SharedPostgres;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-migration persistence for {@code agent.user_tool_env} (zonky embedded PostgreSQL). */
class PostgresUserToolEnvStoreTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PostgresUserToolEnvStore store;

    @BeforeAll
    static void startDatabase() {
        postgres = SharedPostgres.get();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        store = new PostgresUserToolEnvStore(jdbc);
        Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc.update("insert into agent.users (username, password_hash) values ('ada', 'x'), ('bob', 'x')"
                + " on conflict (username) do nothing");
    }

    @BeforeEach
    void resetData() {
        jdbc.execute("truncate agent.user_tool_env");
    }

    @Test
    void roundTripsValuesIncludingJson() {
        store.save("ada", "GCLOUD_SA_KEY", "{\"type\":\"service_account\",\"k\":1}");

        assertEquals("{\"type\":\"service_account\",\"k\":1}", store.find("ada", "GCLOUD_SA_KEY").orElseThrow());
    }

    @Test
    void upsertReplacesAndIsolatesOwners() {
        store.save("ada", "GH_TOKEN", "old");
        store.save("ada", "GH_TOKEN", "new");

        assertEquals("new", store.find("ada", "GH_TOKEN").orElseThrow());
        assertEquals(Map.of("GH_TOKEN", "new"), store.listByOwner("ada"));
        assertTrue(store.find("bob", "GH_TOKEN").isEmpty());
        assertTrue(store.listByOwner("bob").isEmpty());
    }

    @Test
    void deleteRemovesOnlyTheNamedKey() {
        store.save("ada", "GH_TOKEN", "t");
        store.save("ada", "NPM_TOKEN", "n");

        assertTrue(store.delete("ada", "GH_TOKEN"));
        assertFalse(store.delete("ada", "GH_TOKEN"));
        assertEquals(Map.of("NPM_TOKEN", "n"), store.listByOwner("ada"));
    }
}
