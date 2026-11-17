package dev.kualu.web.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Content-moderation settings. Defaults live in {@code application.yml};
 * the mode and the lexicon source are validated only when moderation is
 * enabled, so a disabled feature never blocks startup.
 */
@ConfigurationProperties(prefix = "agent.moderation")
public record ModerationProperties(
        boolean enabled,
        String mode,
        String lexiconPath,
        Llm llm) {

    /** The LLM classifier (L2) settings — flash-tier model, see the spec. */
    public record Llm(boolean enabled, String provider, String model, long timeoutMs) {

        public Duration timeout() {
            return Duration.ofMillis(timeoutMs);
        }
    }

    public ModerationProperties {
        if (enabled) {
            if (mode == null || !(mode.equals("block") || mode.equals("flag"))) {
                throw new IllegalArgumentException(
                        "agent.moderation.mode must be 'block' or 'flag' (got " + mode + ")");
            }
            if (lexiconPath == null || lexiconPath.isBlank()) {
                throw new IllegalArgumentException("agent.moderation.lexicon-path must not be blank");
            }
            if (llm == null) {
                throw new IllegalArgumentException("agent.moderation.llm settings are required");
            }
            if (llm.enabled() && llm.timeoutMs() <= 0) {
                throw new IllegalArgumentException("agent.moderation.llm.timeout-ms must be > 0");
            }
        }
    }

    public boolean isFlagMode() {
        return "flag".equals(mode);
    }
}
