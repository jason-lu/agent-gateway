package dev.kualu.web.llm;

import dev.kualu.web.SharedPostgres;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-migration persistence for {@code agent.llm_providers} (zonky embedded
 * PostgreSQL). The schema is migrated once per class; each test starts from an
 * empty provider table. The V8 → V9 carry-over lives in
 * {@code PostgresLlmProviderMigrationTest} — it needs a freshly built schema per
 * test rather than a data reset.
 */
class PostgresLlmProviderStoreTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PostgresLlmProviderStore store;

    @BeforeAll
    static void startDatabase() {
        postgres = SharedPostgres.get();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        store = new PostgresLlmProviderStore(jdbc, new ObjectMapper());
        Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** Data-only reset (cascades to the preference rows). */
    @BeforeEach
    void resetData() {
        jdbc.execute("truncate agent.llm_providers cascade");
    }

    @Test
    void roundTripsEveryField() {
        LlmProvider provider = new LlmProvider(
                "p1", "OpenAI · 主力", "https://a.example.com/v1", "sk-秘密+/=",
                List.of("gpt-4o", "gpt-4o-mini"), "gpt-4o", true);
        store.save(provider);

        assertEquals(provider, store.find("p1").orElseThrow());
        assertEquals(List.of(provider), store.findAll());
    }

    @Test
    void setPrimaryMovesTheFlagBetweenRows() {
        store.save(provider("a", "alpha", true));
        store.save(provider("b", "beta", false));

        store.setPrimary("b");

        assertFalse(store.find("a").orElseThrow().primary());
        assertTrue(store.find("b").orElseThrow().primary());
    }

    @Test
    void theDatabaseRejectsASecondPrimary() {
        store.save(provider("a", "alpha", true));
        assertThrows(DataIntegrityViolationException.class,
                () -> store.save(provider("b", "beta", true)),
                "the partial unique index must keep a single primary");
    }

    @Test
    void deleteRemovesTheRow() {
        store.save(provider("a", "alpha", true));
        assertTrue(store.delete("a"));
        assertFalse(store.delete("a"));
        assertTrue(store.findAll().isEmpty());
    }

    private static LlmProvider provider(String id, String name, boolean primary) {
        return new LlmProvider(id, name, "https://" + name + ".example.com/v1", "key-" + name,
                List.of(name + "-model"), name + "-model", primary);
    }
}
