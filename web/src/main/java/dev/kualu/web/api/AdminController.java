package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.AdminLlmModelsResponse;
import dev.kualu.web.api.ApiDtos.AdminLlmSettingsResponse;
import dev.kualu.web.api.ApiDtos.AdminUserResponse;
import dev.kualu.web.config.SecurityProperties;
import dev.kualu.web.llm.AdminLlmSettings;
import dev.kualu.web.llm.AdminLlmSettingsStore;
import dev.kualu.web.llm.LlmSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final SecurityProperties securityProperties;
    private final AdminLlmSettingsStore llmStore;
    private final LlmSettingsService llmSettings;

    public AdminController(
            SecurityProperties securityProperties,
            AdminLlmSettingsStore llmStore,
            LlmSettingsService llmSettings) {
        this.securityProperties = securityProperties;
        this.llmStore = llmStore;
        this.llmSettings = llmSettings;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminUserResponse> users() {
        return securityProperties.users().stream()
                .map(user -> new AdminUserResponse(user.username(), user.roles()))
                .toList();
    }

    /**
     * The admin LLM overrides and what they resolve to. A field that is not overridden
     * falls back to the environment config ({@code LLM_BASE_URL} / {@code LLM_API_KEY} /
     * {@code LLM_MODEL}); admin values win per field.
     */
    @GetMapping("/llm-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminLlmSettingsResponse llmSettings() {
        return toResponse(llmSettings.status());
    }

    /**
     * Writes the overrides. Per field: absent (null) keeps the stored value, a blank
     * string clears the override (falls back to the environment), anything else replaces
     * it; the mask placeholder on {@code apiKey} keeps the stored key. Changes apply to
     * the next turn without a restart.
     */
    @PutMapping("/llm-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminLlmSettingsResponse updateLlmSettings(@RequestBody AdminLlmSettingsBody body) {
        AdminLlmSettings existing = llmStore.find().orElse(new AdminLlmSettings(null, null, null));
        llmStore.save(new AdminLlmSettings(
                mergeField(body.baseUrl(), existing.baseUrl()),
                mergeSecret(body.apiKey(), existing.apiKey()),
                mergeField(body.model(), existing.model())));
        return toResponse(llmSettings.status());
    }

    /** Drops every override so the environment configuration applies again. */
    @DeleteMapping("/llm-settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void resetLlmSettings() {
        llmStore.delete();
    }

    /**
     * Lists the model IDs the upstream offers (OpenAI-compatible {@code GET /models})
     * so the admin UI can offer them in a dropdown instead of requiring manual entry.
     * Body fields carry the connection the form currently holds, with the same
     * semantics as {@link #updateLlmSettings} (null keeps the stored value, a blank
     * clears it, the mask keeps the stored key) — the list is what saving those values
     * right now would reach. Omit the body to probe the current effective connection.
     */
    @PostMapping("/llm-settings/models")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminLlmModelsResponse listLlmModels(@RequestBody(required = false) AdminLlmModelsBody body) {
        AdminLlmSettings existing = llmStore.find().orElse(new AdminLlmSettings(null, null, null));
        AdminLlmSettings draft = body == null
                ? existing
                : new AdminLlmSettings(
                        mergeField(body.baseUrl(), existing.baseUrl()),
                        mergeSecret(body.apiKey(), existing.apiKey()),
                        existing.model());
        return new AdminLlmModelsResponse(llmSettings.listModels(draft));
    }

    /** See {@link #listLlmModels}: the draft connection; the model name is what the listing chooses. */
    public record AdminLlmModelsBody(String baseUrl, String apiKey) {
    }

    /** See {@link #updateLlmSettings}: null keeps, blank clears, a value replaces. */
    public record AdminLlmSettingsBody(String baseUrl, String apiKey, String model) {
    }

    private static AdminLlmSettingsResponse toResponse(LlmSettingsService.Status status) {
        AdminLlmSettings admin = status.admin();
        return new AdminLlmSettingsResponse(
                admin.baseUrl(),
                AdminLlmSettings.isBlank(admin.apiKey()) ? null : SettingsController.MASK,
                admin.model(),
                status.effectiveBaseUrl(),
                status.effectiveModel(),
                status.baseUrlSource(),
                status.apiKeySource(),
                status.modelSource(),
                status.configured());
    }

    private static String mergeField(String incoming, String stored) {
        if (incoming == null) {
            return stored;
        }
        return incoming.isBlank() ? null : incoming.trim();
    }

    /** Same as {@link #mergeField}, except the mask placeholder keeps the stored value. */
    private static String mergeSecret(String incoming, String stored) {
        if (incoming == null || SettingsController.MASK.equals(incoming)) {
            return stored;
        }
        return incoming.isBlank() ? null : incoming.trim();
    }
}
