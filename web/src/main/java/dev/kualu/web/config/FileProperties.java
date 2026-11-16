package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Downloadable artifact root. Must point at the same directory the office-mcp
 * server writes to ({@code OFFICE_OUTPUT_DIR}) — the gateway serves the files
 * that tool calls produced there.
 */
@ConfigurationProperties(prefix = "agent.files")
public record FileProperties(@DefaultValue("output/office") String dir) {

    public FileProperties {
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("agent.files.dir must not be blank");
        }
    }
}
