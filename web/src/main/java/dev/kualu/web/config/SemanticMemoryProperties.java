package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Semantic (cross-session) memory. Defaults live in {@code application.yml}.
 * Validated at startup: a disabled feature never blocks startup, an enabled one
 * with an invalid size fails fast instead of degrading silently. The hard-
 * identifier gate has no switch — it is mandatory whenever memory is enabled.
 */
@ConfigurationProperties(prefix = "agent.memory.semantic")
public record SemanticMemoryProperties(boolean enabled, int maxInject) {

    public SemanticMemoryProperties {
        if (enabled && maxInject < 1) {
            throw new IllegalArgumentException(
                    "agent.memory.semantic.max-inject must be >= 1 (got " + maxInject + ")");
        }
    }
}
