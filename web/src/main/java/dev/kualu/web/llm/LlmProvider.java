package dev.kualu.web.llm;

import java.util.List;
import java.util.Objects;

/**
 * One admin-configured LLM upstream: a connection (base URL + API key) and the
 * model IDs it offers. Exactly one provider is the {@code primary} — the
 * deployment default used when a user has not picked a model.
 */
public record LlmProvider(
        String id,
        String name,
        String baseUrl,
        String apiKey,
        List<String> models,
        String defaultModel,
        boolean primary) {

    public LlmProvider {
        Objects.requireNonNull(id, "id");
        // Nulls from a partial request body are normalized to blanks so validate()
        // reports them as 400s instead of the constructor throwing.
        name = name == null ? "" : name;
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        models = models == null
                ? List.of()
                : models.stream().map(model -> model == null ? "" : model).toList();
        defaultModel = defaultModel == null ? "" : defaultModel;
    }

    /** Copy with the primary flag set (the store persists the flag as given). */
    public LlmProvider withPrimary(boolean flag) {
        return new LlmProvider(id, name, baseUrl, apiKey, models, defaultModel, flag);
    }

    /** @throws IllegalArgumentException with a message fit for the API layer */
    public void validate() {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (models.isEmpty()) {
            throw new IllegalArgumentException("provider '" + name + "' needs at least one model");
        }
        for (String model : models) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model names must not be blank");
            }
        }
        if (!models.contains(defaultModel)) {
            throw new IllegalArgumentException(
                    "defaultModel '" + defaultModel + "' is not one of the provider's models");
        }
    }
}
