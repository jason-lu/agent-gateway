package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memory compression thresholds. Defaults live in {@code application.yml}.
 * Thresholds are validated only when compression is enabled, so a disabled
 * feature never blocks startup; invalid values then fail fast instead of
 * degrading silently.
 */
@ConfigurationProperties(prefix = "agent.memory")
public record MemoryProperties(
        boolean enabled,
        int maxMessages,
        int keepMessages,
        String instruction
) {

    public MemoryProperties {
        if (enabled) {
            if (keepMessages < 2) {
                throw new IllegalArgumentException("agent.memory.keep-messages must be >= 2 (got " + keepMessages + ")");
            }
            if (maxMessages <= keepMessages + 2) {
                throw new IllegalArgumentException("agent.memory.max-messages must be > keep-messages + 2 (got "
                        + maxMessages + ", keep-messages " + keepMessages + ")");
            }
        }
    }
}
