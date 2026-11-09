package dev.kualu.web.llm;

import dev.kualu.llm.client.ImageSource;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.web.chat.LlmNotConfiguredException;
import dev.kualu.web.chat.UpstreamLlmException;
import dev.kualu.web.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider rules (one protected primary, unique names, valid model lists) and turn
 * resolution: the requested (provider, model) wins, then the owner's saved choice,
 * then the primary provider's default model.
 */
class LlmSettingsServiceTest {

    private final InMemoryLlmProviderStore store = new InMemoryLlmProviderStore();
    private final InMemoryUserLlmPreferenceStore preferences = new InMemoryUserLlmPreferenceStore();

    @Test
    void firstProviderBecomesThePrimary() {
        LlmProvider first = service().create("openai", "https://a.example.com/v1", "k1", List.of("m1", "m2"), "m1");
        assertTrue(first.primary());

        LlmProvider second = service().create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");
        assertFalse(second.primary(), "only the first provider is primary");
    }

    @Test
    void promoteMovesThePrimaryFlag() {
        LlmProvider first = service().create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        LlmProvider second = service().create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");

        service().promote(second.id());

        assertFalse(service().providers().stream().filter(p -> p.id().equals(first.id()))
                .findFirst().orElseThrow().primary());
        assertTrue(service().providers().stream().filter(p -> p.id().equals(second.id()))
                .findFirst().orElseThrow().primary());
    }

    @Test
    void primaryCannotBeDeletedUntilAnotherOneIsPromoted() {
        LlmSettingsService service = service();
        LlmProvider first = service.create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        LlmProvider second = service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> service.delete(first.id()));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(2, service.providers().size(), "the protected primary must survive");

        service.promote(second.id());
        service.delete(first.id());
        assertEquals(1, service.providers().size());
        assertTrue(service.providers().get(0).primary(), "the promoted provider keeps the flag");
    }

    @Test
    void nonPrimaryProvidersAreFreelyDeletable() {
        LlmSettingsService service = service();
        LlmProvider first = service.create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");

        service.delete(store.findAll().stream().filter(p -> !p.primary()).findFirst().orElseThrow().id());

        assertEquals(List.of(first.id()), service.providers().stream().map(LlmProvider::id).toList());
    }

    @Test
    void duplicateNamesAreRejected() {
        LlmSettingsService service = service();
        service.create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> service.create("openai", "https://b.example.com/v1", "k2", List.of("d1"), "d1"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // Renaming a row to another row's name is the same clash.
        LlmProvider other = service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");
        assertThrows(ResponseStatusException.class,
                () -> service.update(other.id(), "openai", "https://b.example.com/v1", "k2", List.of("d1"), "d1"));

        // Renaming a row onto its own name is not a clash.
        assertEquals("deepseek", service.update(other.id(), "deepseek", "https://b.example.com/v1", "k2",
                List.of("d1"), "d1").name());
    }

    @Test
    void invalidProvidersAreRejectedWith400() {
        LlmSettingsService service = service();
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.create("", "https://a.example.com/v1", "k1", List.of("m1"), "m1"));
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.create("openai", "https://a.example.com/v1", "k1", List.of(), "m1"));
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "not-offered"));
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.create("openai", "https://a.example.com/v1", "", List.of("m1"), "m1"));
    }

    @Test
    void updateKeepsThePrimaryFlagAndAppliesTheFields() {
        LlmSettingsService service = service();
        LlmProvider first = service.create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");

        LlmProvider updated = service.update(first.id(), "openai-pro", "https://c.example.com/v1", "k9",
                List.of("m1", "m3"), "m3");

        assertTrue(updated.primary(), "an update must not move the primary flag");
        assertEquals("openai-pro", updated.name());
        assertEquals("https://c.example.com/v1", updated.baseUrl());
        assertEquals(List.of("m1", "m3"), updated.models());
    }

    @Test
    void requestedPairWinsOverSavedChoiceAndDefault() {
        LlmSettingsService service = service();
        LlmProvider a = service.create("openai", "https://a.example.com/v1", "k1", List.of("m1", "m2"), "m1");
        LlmProvider b = service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");
        preferences.save("ada", new UserLlmPreference(b.id(), "d1"));

        LlmClient client = service.clientFor("ada", a.id(), "m2");
        assertNotNull(client);
        assertEquals("m2", client.config().model());
        assertEquals("https://a.example.com/v1", client.config().baseUrl());
    }

    @Test
    void savedChoiceWinsOverTheDefault() {
        LlmSettingsService service = service();
        service.create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        LlmProvider b = service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");
        preferences.save("ada", new UserLlmPreference(b.id(), "d1"));

        LlmClient client = service.clientFor("ada", null, null);
        assertNotNull(client);
        assertEquals("d1", client.config().model());
    }

    @Test
    void staleChoicesFallBackToThePrimaryDefault() {
        LlmSettingsService service = service();
        LlmProvider a = service.create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        LlmProvider b = service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");
        // A pair that is no longer on offer (admin reworked the list) must not fail the turn.
        preferences.save("ada", new UserLlmPreference(b.id(), "removed-model"));
        LlmClient stale = service.clientFor("ada", a.id(), "removed-model");
        assertNotNull(stale);
        assertEquals("m1", stale.config().model(), "fall back to the primary's default model");

        // A preference pointing at a deleted provider behaves the same way.
        store.delete(b.id());
        assertEquals("m1", service.clientFor("ada", null, null).config().model());
    }

    @Test
    void defaultClientIsThePrimaryDefaultModel() {
        LlmSettingsService service = service();
        service.create("openai", "https://a.example.com/v1", "k1", List.of("m1", "m2"), "m2");
        LlmProvider b = service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");
        service.promote(b.id());

        LlmClient client = service.defaultClient();
        assertNotNull(client);
        assertEquals("d1", client.config().model());
        assertEquals("https://b.example.com/v1", client.config().baseUrl());
    }

    @Test
    void unconfiguredWithoutProviders() {
        LlmSettingsService service = service();
        assertFalse(service.isConfigured());
        assertNull(service.defaultClient());
        assertNull(service.clientFor("ada", null, null));
        assertTrue(service.catalog("ada").models().isEmpty());
        assertNull(service.catalog("ada").selected());
    }

    @Test
    void catalogListsEveryPairWithTheEffectiveChoice() {
        LlmSettingsService service = service();
        LlmProvider a = service.create("openai", "https://a.example.com/v1", "k1", List.of("m1", "m2"), "m1");
        LlmProvider b = service.create("deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");

        LlmSettingsService.Catalog catalog = service.catalog("ada");
        assertEquals(3, catalog.models().size());
        assertEquals(new UserLlmPreference(a.id(), "m1"), catalog.defaultChoice());
        assertEquals(new UserLlmPreference(a.id(), "m1"), catalog.selected(), "unset user picks the default");

        preferences.save("ada", new UserLlmPreference(b.id(), "d1"));
        assertEquals(new UserLlmPreference(b.id(), "d1"), service.catalog("ada").selected());
    }

    @Test
    void savePreferenceRequiresAnOfferedPair() {
        LlmSettingsService service = service();
        LlmProvider a = service.create("openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");

        assertStatus(HttpStatus.NOT_FOUND, () -> service.savePreference("ada", "nope", "m1"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.savePreference("ada", a.id(), "not-offered"));

        UserLlmPreference saved = service.savePreference("ada", a.id(), "m1");
        assertEquals(new UserLlmPreference(a.id(), "m1"), saved);
        assertEquals(saved, preferences.find("ada").orElseThrow());
    }

    @Test
    void listModelsNeedsAConnectionButNoModelName() {
        LlmSettingsService service = service();
        assertThrows(LlmNotConfiguredException.class, () -> service.listModels(null, null),
                "a probe without any base URL or API key must not hit the network");
        assertThrows(LlmNotConfiguredException.class,
                () -> service.listModels("https://env.example.com/v1", " "));

        // A complete connection is enough even with no model name — that is what the
        // listing is for. Port 1 refuses instantly, so the probe fails as an upstream
        // error rather than a configuration one.
        assertThrows(UpstreamLlmException.class,
                () -> service.listModels("http://127.0.0.1:1/v1", "key"),
                "the probe must reach the upstream");
    }

    private LlmSettingsService service() {
        return new LlmSettingsService(store, preferences, new LlmProperties(null, null, null, 90),
                ImageSource.passThrough());
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected, ex.getStatusCode());
    }
}
