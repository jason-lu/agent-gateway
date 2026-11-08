package dev.kualu.web.llm;

import dev.kualu.llm.client.ImageSource;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.web.config.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effective-config resolution: admin overrides win per field, unset fields fall
 * back to the environment, and the client is rebuilt when the merge changes.
 */
class LlmSettingsServiceTest {

    private final InMemoryAdminLlmSettingsStore store = new InMemoryAdminLlmSettingsStore();

    @Test
    void adminValuesWinPerField() {
        store.save(new AdminLlmSettings("https://admin.example.com/v1", "admin-key", null));
        LlmSettingsService service = service(env("https://env.example.com/v1", "env-key", "env-model"));

        LlmConfig config = service.client().config();
        assertEquals("https://admin.example.com/v1", config.baseUrl());
        assertEquals("admin-key", config.apiKey());
        assertEquals("env-model", config.model());

        LlmSettingsService.Status status = service.status();
        assertEquals(LlmSettingsService.SOURCE_ADMIN, status.baseUrlSource());
        assertEquals(LlmSettingsService.SOURCE_ADMIN, status.apiKeySource());
        assertEquals(LlmSettingsService.SOURCE_ENV, status.modelSource());
        assertTrue(status.configured());
    }

    @Test
    void unsetFieldsFallBackToEnv() {
        store.save(new AdminLlmSettings(null, "admin-key", " "));
        LlmSettingsService service = service(env("https://env.example.com/v1", "env-key", "env-model"));

        LlmConfig config = service.client().config();
        assertEquals("https://env.example.com/v1", config.baseUrl());
        assertEquals("admin-key", config.apiKey());
        assertEquals("env-model", config.model());
    }

    @Test
    void unconfiguredWhenNoSourceHasAValue() {
        LlmSettingsService service = service(env(null, null, null));

        assertNull(service.client());
        assertFalse(service.isConfigured());
        assertFalse(service.status().configured());
        assertEquals(LlmSettingsService.SOURCE_NONE, service.status().baseUrlSource());
    }

    @Test
    void partiallyConfiguredIsNotConfigured() {
        store.save(new AdminLlmSettings(null, "admin-key", null));
        LlmSettingsService service = service(env("https://env.example.com/v1", null, null));

        assertNull(service.client());
        assertFalse(service.isConfigured());
        assertEquals(LlmSettingsService.SOURCE_NONE, service.status().modelSource());
    }

    @Test
    void clientIsCachedUntilTheEffectiveConfigChanges() {
        store.save(new AdminLlmSettings("https://admin.example.com/v1", "admin-key", "admin-model"));
        LlmSettingsService service = service(env("https://env.example.com/v1", "env-key", "env-model"));

        var first = service.client();
        assertNotNull(first);
        assertSame(first, service.client(), "unchanged config must reuse the client");

        store.save(new AdminLlmSettings("https://other.example.com/v1", "admin-key", "admin-model"));
        assertNotSame(first, service.client(), "a new upstream config must rebuild the client");

        store.delete();
        var restored = service.client();
        assertNotNull(restored);
        assertNotSame(first, restored, "dropping the overrides must rebuild the client");
        assertEquals("https://env.example.com/v1", restored.config().baseUrl(),
                "dropping the overrides must fall back to the environment");
    }

    @Test
    void statusExposesAdminValuesAndEffectiveValues() {
        store.save(new AdminLlmSettings("https://admin.example.com/v1", "admin-key", null));
        LlmSettingsService service = service(env("https://env.example.com/v1", "env-key", "env-model"));

        LlmSettingsService.Status status = service.status();
        assertEquals("https://admin.example.com/v1", status.admin().baseUrl());
        assertEquals("admin-key", status.admin().apiKey());
        assertEquals("https://admin.example.com/v1", status.effectiveBaseUrl());
        assertEquals("env-model", status.effectiveModel());
    }

    private LlmSettingsService service(LlmProperties env) {
        return new LlmSettingsService(store, env, ImageSource.passThrough());
    }

    private static LlmProperties env(String baseUrl, String apiKey, String model) {
        return new LlmProperties(baseUrl, apiKey, model, 90);
    }
}
