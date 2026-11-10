package dev.kualu.web.llm;

import dev.kualu.web.config.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The environment seeds the first provider only when nothing is configured yet. */
class LlmProviderSeederTest {

    private final InMemoryLlmProviderStore store = new InMemoryLlmProviderStore();

    @Test
    void seedsThePrimaryProviderFromTheEnvironment() {
        run(env("https://env.example.com/v1", "env-key", "env-model"));

        LlmProvider seeded = store.findAll().get(0);
        assertEquals("Env", seeded.name());
        assertEquals("https://env.example.com/v1", seeded.baseUrl());
        assertEquals("env-key", seeded.apiKey());
        assertEquals(java.util.List.of("env-model"), seeded.models());
        assertEquals("env-model", seeded.defaultModel());
        assertTrue(seeded.primary());
    }

    @Test
    void namesTheSeededProviderAfterTheModelFamily() {
        assertEquals("Mimo", LlmProviderSeeder.defaultName("mimo-v2.6-flash"));
        assertEquals("Gpt", LlmProviderSeeder.defaultName("gpt-4o-mini"));
        assertEquals("Claude", LlmProviderSeeder.defaultName("claude-3-5-sonnet"));
        assertEquals("7b-instruct", LlmProviderSeeder.defaultName("7b-instruct"));
    }

    @Test
    void neverOverwritesAnExistingProvider() {
        store.save(new LlmProvider("p1", "openai", "https://a.example.com/v1", "k1",
                java.util.List.of("m1"), "m1", true));

        run(env("https://env.example.com/v1", "env-key", "env-model"));

        assertEquals(1, store.findAll().size());
        assertEquals("openai", store.findAll().get(0).name());
    }

    @Test
    void anIncompleteEnvironmentSeedsNothing() {
        run(env("https://env.example.com/v1", null, "env-model"));
        assertTrue(store.findAll().isEmpty());
    }

    private void run(LlmProperties env) {
        new LlmProviderSeeder(store, env).run(null);
    }

    private static LlmProperties env(String baseUrl, String apiKey, String model) {
        return new LlmProperties(baseUrl, apiKey, model, 90);
    }
}
