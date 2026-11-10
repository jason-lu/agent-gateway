package dev.kualu.web.llm;

import dev.kualu.web.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.List;
import java.util.UUID;

/**
 * Seeds the first provider from the environment config ({@code LLM_BASE_URL} /
 * {@code LLM_API_KEY} / {@code LLM_MODEL}) when no provider exists yet — a fresh
 * deployment works out of the box, after which the admin UI owns the list. A
 * migration that carried the old single-row admin settings over already left a
 * provider behind, so this then does nothing.
 */
public class LlmProviderSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderSeeder.class);

    private final LlmProviderStore store;
    private final LlmProperties env;

    public LlmProviderSeeder(LlmProviderStore store, LlmProperties env) {
        this.store = store;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!store.findAll().isEmpty()) {
            return;
        }
        if (isBlank(env.baseUrl()) || isBlank(env.apiKey()) || isBlank(env.model())) {
            log.info("llm providers: none configured and the environment is incomplete; "
                    + "add one in 管理 → LLM 供应商");
            return;
        }
        String model = env.model().trim();
        String name = defaultName(model);
        store.save(new LlmProvider(
                UUID.randomUUID().toString(),
                name,
                env.baseUrl().trim(),
                env.apiKey().trim(),
                List.of(model),
                model,
                true));
        log.info("llm providers: seeded the primary provider '{}' from the environment config", name);
    }

    /**
     * Name for the environment-seeded provider: the model's family prefix with its
     * first letter capitalized ({@code mimo-v2.6-flash} → {@code Mimo}), so the chat
     * picker's {@code name / model} label reads like a vendor rather than a UI
     * placeholder. The admin console renames the record to the exact brand.
     */
    static String defaultName(String model) {
        int end = 0;
        while (end < model.length() && Character.isLetter(model.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return model;
        }
        return Character.toUpperCase(model.charAt(0)) + model.substring(1, end);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
