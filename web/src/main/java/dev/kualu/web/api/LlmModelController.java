package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.LlmModelCatalogResponse;
import dev.kualu.web.api.ApiDtos.LlmModelChoice;
import dev.kualu.web.api.ApiDtos.LlmModelOption;
import dev.kualu.web.llm.LlmSettingsService;
import dev.kualu.web.llm.UserLlmPreference;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The model picker every user sees in the chat window: what the deployment offers
 * (every provider's models), what the default is (the primary provider's default
 * model), and the caller's own choice — remembered server-side so it follows the
 * account across devices.
 */
@RestController
@RequestMapping("/api/v1/llm")
public class LlmModelController {

    private final LlmSettingsService llmSettings;

    public LlmModelController(LlmSettingsService llmSettings) {
        this.llmSettings = llmSettings;
    }

    @GetMapping("/models")
    public LlmModelCatalogResponse catalog(@AuthenticationPrincipal String username) {
        LlmSettingsService.Catalog catalog = llmSettings.catalog(username);
        List<LlmModelOption> models = catalog.models().stream()
                .map(option -> new LlmModelOption(option.providerId(), option.providerName(), option.model()))
                .toList();
        return new LlmModelCatalogResponse(models, toChoice(catalog.defaultChoice()), toChoice(catalog.selected()));
    }

    /** Saves the model the caller picked; the pair must be one currently on offer. */
    @PutMapping("/model")
    public LlmModelChoice choose(@AuthenticationPrincipal String username, @RequestBody LlmModelChoiceBody body) {
        UserLlmPreference saved = llmSettings.savePreference(username, body.providerId(), body.model());
        return toChoice(saved);
    }

    public record LlmModelChoiceBody(String providerId, String model) {
    }

    private static LlmModelChoice toChoice(UserLlmPreference preference) {
        return preference == null ? null : new LlmModelChoice(preference.providerId(), preference.model());
    }
}
