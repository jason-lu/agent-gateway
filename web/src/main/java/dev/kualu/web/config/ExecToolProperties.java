package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Exec tool limits. Defaults live in {@code application.yml}; the annotations
 * keep binding working when the section is absent.
 */
@ConfigurationProperties(prefix = "agent.tools.exec")
public record ExecToolProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") long timeoutSeconds,
        @DefaultValue("8000") int outputLimit,
        String workdir) {

    public ExecToolProperties {
        if (enabled) {
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("agent.tools.exec.timeout-seconds must be positive");
            }
            if (outputLimit <= 0) {
                throw new IllegalArgumentException("agent.tools.exec.output-limit must be positive");
            }
        }
    }
}
