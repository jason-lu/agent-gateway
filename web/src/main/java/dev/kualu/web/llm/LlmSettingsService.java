package dev.kualu.web.llm;

import dev.kualu.http.HttpClient;
import dev.kualu.http.HttpClientException;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.client.ImageSource;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.web.chat.LlmNotConfiguredException;
import dev.kualu.web.chat.UpstreamLlmException;
import dev.kualu.web.config.LlmProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The LLM provider list and what each turn should use.
 *
 * <p>Admins maintain any number of {@link LlmProvider rows} (a connection plus the
 * model IDs it offers); exactly one is the {@code primary} — the deployment
 * default. The primary cannot be deleted, so at least one provider always remains;
 * promoting another one is what releases it. A turn uses the (provider, model) the
 * chat request names, falling back to the owner's saved choice and then to the
 * primary's default model.
 *
 * <p>Edits take effect on the next turn — nothing about the configuration is
 * cached beyond the shared HTTP client.
 */
@Service
public class LlmSettingsService {

    private final LlmProviderStore store;
    private final UserLlmPreferenceStore preferences;
    private final LlmProperties env;
    private final ImageSource imageSource;

    /** Shared across every upstream: one pool, whichever provider a turn picks. */
    private final HttpClient http = new HttpClient();

    public LlmSettingsService(
            LlmProviderStore store,
            UserLlmPreferenceStore preferences,
            LlmProperties env,
            ImageSource imageSource) {
        this.store = store;
        this.preferences = preferences;
        this.env = env;
        this.imageSource = imageSource;
    }

    // ---------------------------------------------------------------- admin

    public List<LlmProvider> providers() {
        return store.findAll();
    }

    /** The first provider ever created becomes the primary. */
    public LlmProvider create(String name, String baseUrl, String apiKey, List<String> models, String defaultModel) {
        requireUniqueName(name, null);
        LlmProvider provider = new LlmProvider(
                UUID.randomUUID().toString(), name, baseUrl, apiKey, models, defaultModel, store.findAll().isEmpty());
        validate(provider);
        store.save(provider);
        return provider;
    }

    /** Updates the row in place; only {@link #promote(String)} moves the primary flag. */
    public LlmProvider update(String id, String name, String baseUrl, String apiKey, List<String> models,
            String defaultModel) {
        LlmProvider existing = require(id);
        requireUniqueName(name, id);
        LlmProvider provider = new LlmProvider(
                id, name, baseUrl, apiKey, models, defaultModel, existing.primary());
        validate(provider);
        store.save(provider);
        return provider;
    }

    /**
     * Removes a provider. The primary is protected: promoting another one first is
     * exactly what makes it deletable, and the last provider is always primary.
     */
    public void delete(String id) {
        LlmProvider existing = require(id);
        if (existing.primary()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "不能删除主供应商（primary）；请先把其他供应商设为默认，再删除它");
        }
        store.delete(id);
    }

    /** Makes {@code id} the only primary — the default for users who have not chosen. */
    public LlmProvider promote(String id) {
        require(id);
        store.setPrimary(id);
        return require(id);
    }

    /**
     * Model IDs the upstream offers (OpenAI-compatible {@code GET /models}) for the
     * connection the admin form currently holds. The model name is exactly what this
     * listing helps choose.
     *
     * @throws LlmNotConfiguredException when base URL or API key is missing
     * @throws UpstreamLlmException when the upstream cannot list its models
     */
    public List<String> listModels(String baseUrl, String apiKey) {
        if (isBlank(baseUrl) || isBlank(apiKey)) {
            throw new LlmNotConfiguredException("LLM is not configured: set the base URL and API key first");
        }
        try {
            return LlmClient.listModels(http, baseUrl.trim(), apiKey.trim());
        } catch (LlmException | HttpClientException ex) {
            throw new UpstreamLlmException(ex.getMessage(), ex);
        }
    }

    // ---------------------------------------------------------------- users

    /** Every (provider, model) pair users can pick, plus the default and current choice. */
    public Catalog catalog(String owner) {
        List<Catalog.Option> options = new ArrayList<>();
        LlmProvider primary = primary();
        for (LlmProvider provider : store.findAll()) {
            for (String model : provider.models()) {
                options.add(new Catalog.Option(provider.id(), provider.name(), model));
            }
        }
        UserLlmPreference defaultChoice = primary == null
                ? null
                : new UserLlmPreference(primary.id(), primary.defaultModel());
        Resolved selected = resolve(owner, null, null);
        return new Catalog(options, defaultChoice, selected == null ? null : selected.choice());
    }

    /** Records the model the user picked in the chat window. */
    public UserLlmPreference savePreference(String owner, String providerId, String model) {
        LlmProvider provider = store.find(providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such provider: " + providerId));
        if (model == null || !provider.models().contains(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model '" + model + "' is not offered by provider '" + provider.name() + "'");
        }
        UserLlmPreference preference = new UserLlmPreference(providerId, model);
        preferences.save(owner, preference);
        return preference;
    }

    // ---------------------------------------------------------------- turns

    /**
     * The client one turn should use: the requested (provider, model) when it is
     * still on offer, else the owner's saved choice, else the primary's default.
     * {@code null} when no provider is configured at all.
     */
    public LlmClient clientFor(String owner, String providerId, String model) {
        Resolved resolved = resolve(owner, providerId, model);
        return resolved == null ? null : client(resolved.provider(), resolved.model());
    }

    /** The deployment default — the primary provider's default model. */
    public LlmClient defaultClient() {
        LlmProvider primary = primary();
        return primary == null ? null : client(primary, primary.defaultModel());
    }

    /**
     * The client for an explicitly configured model (e.g. the memory summariser):
     * the named provider's model when both are set and still on offer, else any
     * provider offering the model (primary first), else {@code null}. Unlike
     * {@link #clientFor} this never falls back to a user's saved preference.
     */
    public LlmClient clientForModel(String providerId, String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        if (providerId != null && !providerId.isBlank()) {
            Resolved resolved = lookup(providerId, model);
            return resolved == null ? null : client(resolved.provider(), resolved.model());
        }
        List<LlmProvider> providers = store.findAll().stream()
                .sorted((a, b) -> Boolean.compare(b.primary(), a.primary()))
                .toList();
        for (LlmProvider provider : providers) {
            if (provider.models().contains(model)) {
                return client(provider, model);
            }
        }
        return null;
    }

    public boolean isConfigured() {
        return primary() != null;
    }

    // ---------------------------------------------------------------- internals

    private Resolved resolve(String owner, String providerId, String model) {
        Resolved requested = lookup(providerId, model);
        if (requested != null) {
            return requested;
        }
        UserLlmPreference saved = preferences.find(owner).orElse(null);
        Resolved remembered = saved == null ? null : lookup(saved.providerId(), saved.model());
        if (remembered != null) {
            return remembered;
        }
        LlmProvider primary = primary();
        return primary == null ? null : new Resolved(primary, primary.defaultModel());
    }

    private Resolved lookup(String providerId, String model) {
        if (providerId == null || model == null) {
            return null;
        }
        LlmProvider provider = store.find(providerId).orElse(null);
        return provider != null && provider.models().contains(model) ? new Resolved(provider, model) : null;
    }

    private LlmProvider primary() {
        return store.findAll().stream().filter(LlmProvider::primary).findFirst().orElse(null);
    }

    private LlmClient client(LlmProvider provider, String model) {
        return new LlmClient(http,
                new LlmConfig(provider.baseUrl(), provider.apiKey(), model, Duration.ofSeconds(env.idleTimeoutSeconds())),
                imageSource);
    }

    private LlmProvider require(String id) {
        return store.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such provider: " + id));
    }

    private void requireUniqueName(String name, String selfId) {
        if (name != null) {
            for (LlmProvider provider : store.findAll()) {
                if (provider.name().equals(name) && !provider.id().equals(selfId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "provider '" + name + "' already exists");
                }
            }
        }
    }

    private static void validate(LlmProvider provider) {
        try {
            provider.validate();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Every (provider, model) on offer, plus the default and the caller's effective choice. */
    public record Catalog(List<Option> models, UserLlmPreference defaultChoice, UserLlmPreference selected) {

        public record Option(String providerId, String providerName, String model) {
        }
    }

    private record Resolved(LlmProvider provider, String model) {

        UserLlmPreference choice() {
            return new UserLlmPreference(provider.id(), model);
        }
    }
}
