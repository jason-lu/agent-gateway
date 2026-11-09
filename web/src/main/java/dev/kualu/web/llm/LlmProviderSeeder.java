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

    /** Provider name given to the environment-seeded entry. */
    public static final String DEFAULT_NAME = "默认";

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
        store.save(new LlmProvider(
                UUID.randomUUID().toString(),
                DEFAULT_NAME,
                env.baseUrl().trim(),
                env.apiKey().trim(),
                List.of(model),
                model,
                true));
        log.info("llm providers: seeded the primary provider '{}' from the environment config", DEFAULT_NAME);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
