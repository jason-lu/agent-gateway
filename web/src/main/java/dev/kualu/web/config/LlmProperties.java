package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Environment-level LLM upstream config (bound from {@code LLM_BASE_URL} /
 * {@code LLM_API_KEY} / {@code LLM_MODEL}). Admin overrides win per field; see
 * {@code dev.kualu.web.llm.LlmSettingsService}.
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        @DefaultValue("90") long idleTimeoutSeconds) {
}
