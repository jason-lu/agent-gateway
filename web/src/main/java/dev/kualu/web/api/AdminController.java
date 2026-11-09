package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.AdminLlmModelsResponse;
import dev.kualu.web.api.ApiDtos.AdminUserResponse;
import dev.kualu.web.api.ApiDtos.LlmProviderBody;
import dev.kualu.web.api.ApiDtos.LlmProviderProbeBody;
import dev.kualu.web.api.ApiDtos.LlmProviderResponse;
import dev.kualu.web.config.SecurityProperties;
import dev.kualu.web.llm.LlmProvider;
import dev.kualu.web.llm.LlmSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Deployment-level LLM provider management. Exactly one provider is the primary
 * (the default model source for users who have not chosen); it cannot be deleted
 * until another one is promoted, so the deployment never ends up without an
 * upstream. Secrets are masked ({@code SettingsController.MASK} round-trips as
 * "keep the stored key").
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final SecurityProperties securityProperties;
    private final LlmSettingsService llmSettings;

    public AdminController(SecurityProperties securityProperties, LlmSettingsService llmSettings) {
        this.securityProperties = securityProperties;
        this.llmSettings = llmSettings;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminUserResponse> users() {
        return securityProperties.users().stream()
                .map(user -> new AdminUserResponse(user.username(), user.roles()))
                .toList();
    }

    @GetMapping("/llm-providers")
    @PreAuthorize("hasRole('ADMIN')")
    public List<LlmProviderResponse> llmProviders() {
        return llmSettings.providers().stream().map(AdminController::toResponse).toList();
    }

    /** The first provider ever created becomes the primary. */
    @PostMapping("/llm-providers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public LlmProviderResponse createLlmProvider(@RequestBody LlmProviderBody body) {
        return toResponse(llmSettings.create(
                body.name(), body.baseUrl(), mergeSecret(body.apiKey(), null),
                body.models(), body.defaultModel()));
    }

    /** Field edits only — use {@code /primary} to move the primary flag. */
    @PutMapping("/llm-providers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public LlmProviderResponse updateLlmProvider(@PathVariable String id, @RequestBody LlmProviderBody body) {
        LlmProvider existing = llmSettings.providers().stream()
                .filter(provider -> provider.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no such provider: " + id));
        return toResponse(llmSettings.update(
                id, body.name(), body.baseUrl(), mergeSecret(body.apiKey(), existing.apiKey()),
                body.models(), body.defaultModel()));
    }

    /** 409 while the target is the primary: promote another provider first. */
    @DeleteMapping("/llm-providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteLlmProvider(@PathVariable String id) {
        llmSettings.delete(id);
    }

    /** Makes this provider the deployment default (the one users fall back to). */
    @PostMapping("/llm-providers/{id}/primary")
    @PreAuthorize("hasRole('ADMIN')")
    public LlmProviderResponse promoteLlmProvider(@PathVariable String id) {
        return toResponse(llmSettings.promote(id));
    }

    /**
     * Lists the model IDs the given connection offers (OpenAI-compatible
     * {@code GET /models}) so the admin form can offer them instead of requiring
     * manual entry. {@code id} anchors the merge to a stored provider: per field, a
     * blank (or the mask on the key) keeps the stored value, anything else is used
     * as given — the list is what saving those values right now would reach.
     */
    @PostMapping("/llm-providers/models")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminLlmModelsResponse listLlmModels(@RequestBody(required = false) LlmProviderProbeBody body) {
        String baseUrl = body == null ? null : body.baseUrl();
        String apiKey = body == null ? null : body.apiKey();
        if (body != null && body.id() != null) {
            LlmProvider stored = llmSettings.providers().stream()
                    .filter(provider -> provider.id().equals(body.id()))
                    .findFirst()
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            HttpStatus.NOT_FOUND, "no such provider: " + body.id()));
            if (isBlank(baseUrl)) {
                baseUrl = stored.baseUrl();
            }
            if (isBlank(apiKey) || SettingsController.MASK.equals(apiKey)) {
                apiKey = stored.apiKey();
            }
        }
        return new AdminLlmModelsResponse(llmSettings.listModels(baseUrl, apiKey));
    }

    private static LlmProviderResponse toResponse(LlmProvider provider) {
        return new LlmProviderResponse(
                provider.id(),
                provider.name(),
                provider.baseUrl(),
                SettingsController.MASK,
                provider.models(),
                provider.defaultModel(),
                provider.primary());
    }

    /** The mask (or a blank, or {@code null}) keeps the stored key; anything else replaces it. */
    private static String mergeSecret(String incoming, String stored) {
        if (incoming == null || incoming.isBlank() || SettingsController.MASK.equals(incoming)) {
            return stored;
        }
        return incoming.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
