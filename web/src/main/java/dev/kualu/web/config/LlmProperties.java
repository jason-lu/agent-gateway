package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmProperties(String baseUrl, String apiKey, String model) {

    public boolean isConfigured() {
        return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
