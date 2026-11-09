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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Resolves the effective LLM upstream and hands out the matching client.
 *
 * <p>Resolution is per field: an admin-configured value (see
 * {@link AdminLlmSettingsStore}) wins over the environment configuration
 * ({@code llm.*} bound from {@code LLM_BASE_URL} / {@code LLM_API_KEY} /
 * {@code LLM_MODEL}); when neither source carries a field the upstream is not
 * configured. Admin edits take effect on the next call — the client is rebuilt
 * whenever the effective config changes, so no restart is needed.
 */
@Service
public class LlmSettingsService {

    public static final String SOURCE_ADMIN = "admin";
    public static final String SOURCE_ENV = "env";
    public static final String SOURCE_NONE = "none";

    private final AdminLlmSettingsStore store;
    private final LlmProperties env;
    private final ImageSource imageSource;

    private volatile Cached cached;

    public LlmSettingsService(AdminLlmSettingsStore store, LlmProperties env, ImageSource imageSource) {
        this.store = store;
        this.env = env;
        this.imageSource = imageSource;
    }

    /** The active client, or {@code null} when neither source configures the upstream. */
    public LlmClient client() {
        LlmConfig config = resolve();
        if (config == null) {
            cached = null;
            return null;
        }
        Cached current = cached;
        if (current != null && current.config().equals(config)) {
            return current.client();
        }
        LlmClient client = new LlmClient(new HttpClient(), config, imageSource);
        cached = new Cached(config, client);
        return client;
    }

    public boolean isConfigured() {
        return resolve() != null;
    }

    /** The upstream as the admin API sees it: stored overrides plus what is actually in effect. */
    public Status status() {
        AdminLlmSettings admin = admin();
        Field baseUrl = pick(admin.baseUrl(), env.baseUrl());
        Field apiKey = pick(admin.apiKey(), env.apiKey());
        Field model = pick(admin.model(), env.model());
        return new Status(
                admin,
                baseUrl.value(),
                model.value(),
                baseUrl.source(),
                apiKey.source(),
                model.source(),
                baseUrl.value() != null && apiKey.value() != null && model.value() != null);
    }

    /**
     * Model IDs the upstream offers (OpenAI-compatible {@code GET /models}), resolved
     * like the settings themselves: the given admin values win per field over the
     * environment, so passing what a form holds lists the models that saving those
     * values right now would reach. Only base URL and API key matter — the model name
     * is exactly what this listing helps choose.
     *
     * @throws LlmNotConfiguredException when neither source carries the connection
     * @throws UpstreamLlmException when the upstream cannot list its models
     */
    public List<String> listModels(AdminLlmSettings admin) {
        Field baseUrl = pick(admin.baseUrl(), env.baseUrl());
        Field apiKey = pick(admin.apiKey(), env.apiKey());
        if (baseUrl.value() == null || apiKey.value() == null) {
            throw new LlmNotConfiguredException("LLM is not configured: set the base URL and API key first");
        }
        try {
            return LlmClient.listModels(new HttpClient(), baseUrl.value(), apiKey.value());
        } catch (LlmException | HttpClientException ex) {
            throw new UpstreamLlmException(ex.getMessage(), ex);
        }
    }

    /**
     * Effective config (admin wins per field), or {@code null} when any field is
     * missing from both sources.
     */
    private LlmConfig resolve() {
        AdminLlmSettings admin = admin();
        Field baseUrl = pick(admin.baseUrl(), env.baseUrl());
        Field apiKey = pick(admin.apiKey(), env.apiKey());
        Field model = pick(admin.model(), env.model());
        if (baseUrl.value() == null || apiKey.value() == null || model.value() == null) {
            return null;
        }
        return new LlmConfig(baseUrl.value(), apiKey.value(), model.value(),
                Duration.ofSeconds(env.idleTimeoutSeconds()));
    }

    private AdminLlmSettings admin() {
        return store.find().orElse(new AdminLlmSettings(null, null, null));
    }

    private static Field pick(String adminValue, String envValue) {
        if (!AdminLlmSettings.isBlank(adminValue)) {
            return new Field(adminValue.trim(), SOURCE_ADMIN);
        }
        if (!AdminLlmSettings.isBlank(envValue)) {
            return new Field(envValue.trim(), SOURCE_ENV);
        }
        return new Field(null, SOURCE_NONE);
    }

    /** Effective non-secret values plus the source of every field. */
    public record Status(
            AdminLlmSettings admin,
            String effectiveBaseUrl,
            String effectiveModel,
            String baseUrlSource,
            String apiKeySource,
            String modelSource,
            boolean configured) {
    }

    private record Field(String value, String source) {
    }

    private record Cached(LlmConfig config, LlmClient client) {
    }
}
