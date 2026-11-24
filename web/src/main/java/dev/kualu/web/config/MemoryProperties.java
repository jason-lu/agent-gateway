package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memory compression thresholds. Defaults live in {@code application.yml}.
 * Thresholds are validated only when compression is enabled, so a disabled
 * feature never blocks startup; invalid values then fail fast instead of
 * degrading silently.
 *
 * <p>Both thresholds are token counts: {@code max-tokens} is the context
 * occupancy that triggers a fold, {@code keep-tokens} the budget the verbatim
 * tail may occupy afterwards.
 */
@ConfigurationProperties(prefix = "agent.memory")
public record MemoryProperties(
        boolean enabled,
        int maxTokens,
        int keepTokens,
        int maxSummaryChars,
        String instruction,
        String summaryProvider,
        String summaryModel
) {

    public MemoryProperties {
        if (enabled) {
            if (keepTokens < 1) {
                throw new IllegalArgumentException("agent.memory.keep-tokens must be >= 1 (got " + keepTokens + ")");
            }
            if (maxTokens <= keepTokens) {
                throw new IllegalArgumentException("agent.memory.max-tokens must be > keep-tokens (got "
                        + maxTokens + ", keep-tokens " + keepTokens + ")");
            }
            if (maxSummaryChars <= 0) {
                throw new IllegalArgumentException(
                        "agent.memory.max-summary-chars must be > 0 (got " + maxSummaryChars + ")");
            }
        }
    }
}
