package dev.kualu.web.llm;

import dev.kualu.web.SharedPostgres;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The V8 → V9 data carry-over: each test stands on a schema it builds itself
 * (migrate to V8, write the legacy row, migrate to V9), so a fresh schema per
 * test is the fixture — which is why this is separate from the store tests that
 * reset rows between tests.
 */
class PostgresLlmProviderMigrationTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PostgresLlmProviderStore store;

    @BeforeAll
    static void startDatabase() {
        postgres = SharedPostgres.get();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        store = new PostgresLlmProviderStore(jdbc, new ObjectMapper());
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
