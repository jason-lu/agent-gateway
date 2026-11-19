package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Web search tool limits and credentials. Defaults live in {@code application.yml};
 * the annotations keep binding working when the section is absent. The tool is
 * registered only when enabled and an API key is present — see {@code AppConfig}.
 */
@ConfigurationProperties(prefix = "agent.tools.websearch")
public record WebSearchToolProperties(
        @DefaultValue("true") boolean enabled,
        String apiKey,
        String baseUrl,
        @DefaultValue("5") int maxResults,
        @DefaultValue("8000") int outputLimit,
        @DefaultValue("30") long timeoutSeconds,
        @DefaultValue("true") boolean includeAnswer) {

    public WebSearchToolProperties {
        if (enabled) {
            if (maxResults <= 0) {
                throw new IllegalArgumentException("agent.tools.websearch.max-results must be positive");
            }
            if (outputLimit <= 0) {
                throw new IllegalArgumentException("agent.tools.websearch.output-limit must be positive");
            }
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("agent.tools.websearch.timeout-seconds must be positive");
            }
        }
    }
}
