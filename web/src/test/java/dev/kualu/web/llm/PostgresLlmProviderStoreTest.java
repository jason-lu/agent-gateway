package dev.kualu.web.llm;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-migration persistence for {@code agent.llm_providers} (zonky embedded PostgreSQL). */
class PostgresLlmProviderStoreTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PostgresLlmProviderStore store;

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        store = new PostgresLlmProviderStore(jdbc, new ObjectMapper());
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
        migrateTo(null);
        jdbc.update("insert into agent.users (username, password_hash) values ('ada', 'x')");
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

    @Test
    void carriesACompleteLegacyAdminRowOverAsThePrimaryProvider() {
        jdbc.execute("drop schema if exists agent cascade");
        migrateTo("8");
        jdbc.update("insert into agent.admin_llm_settings (id, base_url, api_key, model)"
                + " values (1, ' https://old.example.com/v1 ', ' old-key ', ' old-model ')");
        migrateTo(null);

        List<LlmProvider> providers = store.findAll();
        assertEquals(1, providers.size());
        LlmProvider migrated = providers.get(0);
        assertEquals("默认", migrated.name());
        assertEquals("https://old.example.com/v1", migrated.baseUrl());
        assertEquals("old-key", migrated.apiKey());
        assertEquals(List.of("old-model"), migrated.models());
        assertEquals("old-model", migrated.defaultModel());
        assertTrue(migrated.primary());
    }

    @Test
    void dropsAPartialLegacyAdminRow() {
        jdbc.execute("drop schema if exists agent cascade");
        migrateTo("8");
        // A partial row relied on per-field env fallback, which is gone: it cannot
        // become a provider, and the environment seed may rebuild one instead.
        jdbc.update("insert into agent.admin_llm_settings (id, base_url, api_key, model)"
                + " values (1, 'https://old.example.com/v1', null, null)");
        migrateTo(null);

        assertTrue(store.findAll().isEmpty());
    }

    private static LlmProvider provider(String id, String name, boolean primary) {
        return new LlmProvider(id, name, "https://" + name + ".example.com/v1", "key-" + name,
                List.of(name + "-model"), name + "-model", primary);
    }

    /** @param target version to stop at, or {@code null} for the latest */
    private static void migrateTo(String target) {
        var configurer = Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration");
        if (target != null) {
            configurer.target(MigrationVersion.fromVersion(target));
        }
        configurer.load().migrate();
    }
}
