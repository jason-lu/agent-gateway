package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Exec tool limits. Defaults live in {@code application.yml}; the annotations
 * keep binding working when the section is absent.
 *
 * <p>{@code workspaces-root} activates per-user isolation: each owner gets their
 * own workspace directory and a private CLI config/credential environment.
 * {@code workdir} is the legacy single-user default, used only when no
 * workspaces root is configured. {@code config-env} adds or retargets entries of
 * the per-workspace config-location registry (values support {@code {workspace}}).
 */
@ConfigurationProperties(prefix = "agent.tools.exec")
public record ExecToolProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") long timeoutSeconds,
        @DefaultValue("8000") int outputLimit,
        String workdir,
        String workspacesRoot,
        Map<String, String> configEnv) {

    public ExecToolProperties {
        if (enabled) {
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("agent.tools.exec.timeout-seconds must be positive");
            }
            if (outputLimit <= 0) {
                throw new IllegalArgumentException("agent.tools.exec.output-limit must be positive");
            }
        }
        configEnv = configEnv == null ? Map.of() : Map.copyOf(configEnv);
    }
}
