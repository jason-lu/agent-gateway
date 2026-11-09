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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-migration persistence for {@code agent.user_llm_preferences} (zonky embedded PostgreSQL). */
class PostgresUserLlmPreferenceStoreTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PostgresLlmProviderStore providers;
    private static PostgresUserLlmPreferenceStore store;

    @BeforeAll
    static void startDatabase() {
        postgres = SharedPostgres.get();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        providers = new PostgresLlmProviderStore(jdbc, new ObjectMapper());
        store = new PostgresUserLlmPreferenceStore(jdbc);
        Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // Idempotent: the shared database may already carry these accounts.
        jdbc.update("insert into agent.users (username, password_hash) values ('ada', 'x'), ('bob', 'x')"
                + " on conflict (username) do nothing");
    }

    /** Data-only reset (cascades to the preference rows); the schema is migrated once per class. */
    @BeforeEach
    void resetData() {
        jdbc.execute("truncate agent.llm_providers cascade");
        providers.save(provider("p1", true));
    }

    @Test
    void roundTripsAndOverwritesPerOwner() {
        store.save("ada", new UserLlmPreference("p1", "gpt-4o"));
        store.save("bob", new UserLlmPreference("p1", "gpt-4o-mini"));

        assertEquals(new UserLlmPreference("p1", "gpt-4o"), store.find("ada").orElseThrow());
        assertEquals(new UserLlmPreference("p1", "gpt-4o-mini"), store.find("bob").orElseThrow());
        assertTrue(store.find("nobody").isEmpty());

        store.save("ada", new UserLlmPreference("p1", "gpt-4o-mini"));
        assertEquals(new UserLlmPreference("p1", "gpt-4o-mini"), store.find("ada").orElseThrow());
    }

    @Test
    void theOwnerMustBeAKnownUser() {
        assertThrows(DataIntegrityViolationException.class,
                () -> store.save("ghost", new UserLlmPreference("p1", "gpt-4o")));
    }

    @Test
    void deletingAProviderDropsTheChoicesMadeOnIt() {
        store.save("ada", new UserLlmPreference("p1", "gpt-4o"));
        providers.save(provider("p2", false));
        store.save("bob", new UserLlmPreference("p2", "deep-model"));

        providers.delete("p2");

        assertTrue(store.find("bob").isEmpty(), "a choice on a removed provider must not linger");
        assertEquals(new UserLlmPreference("p1", "gpt-4o"), store.find("ada").orElseThrow());
    }

    private static LlmProvider provider(String id, boolean primary) {
        return new LlmProvider(id, "provider-" + id, "https://" + id + ".example.com/v1", "key-" + id,
                List.of("gpt-4o", "gpt-4o-mini", "deep-model"), "gpt-4o", primary);
    }
}
