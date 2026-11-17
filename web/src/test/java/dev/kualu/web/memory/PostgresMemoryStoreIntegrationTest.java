package dev.kualu.web.memory;

import dev.kualu.web.SharedPostgres;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The store contract against a real PostgreSQL (zonky embedded, same as the
 * session persistence tests). The schema is migrated once per class; each test
 * starts from truncated memory tables.
 */
class PostgresMemoryStoreIntegrationTest extends MemoryStoreContractTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() {
        postgres = SharedPostgres.get();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void resetData() {
        jdbc.execute("truncate agent.memory_entries");
        jdbc.execute("truncate agent.user_profile");
    }

    @Override
    protected MemoryStore newStore() {
        return new PostgresMemoryStore(jdbc);
    }
}
