package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * MCP client settings. The primary server list lives in {@code mcp.json}
 * ({@code config} points at it); {@code servers} is a deployment-level override
 * layer that supplements or replaces same-named entries.
 */
@ConfigurationProperties(prefix = "agent.tools.mcp")
public record McpProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("./mcp.json") String config,
        @DefaultValue("60") long defaultTimeoutSeconds,
        @DefaultValue("8000") int outputLimit,
        @DefaultValue("300") long refreshSeconds,
        List<Server> servers) {

    public McpProperties {
        if (enabled) {
            if (defaultTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("agent.tools.mcp.default-timeout-seconds must be positive");
            }
            if (outputLimit <= 0) {
                throw new IllegalArgumentException("agent.tools.mcp.output-limit must be positive");
            }
            if (refreshSeconds < 0) {
                throw new IllegalArgumentException("agent.tools.mcp.refresh-seconds must be >= 0");
            }
        }
        servers = servers == null ? List.of() : servers;
    }

    /** One inline server entry; fields mirror the {@code mcp.json} shapes. */
    public record Server(
            String name,
            String transport,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            Map<String, String> headers,
            String auth,
            String clientId,
            List<String> scopes) {

        public Server {
            args = args == null ? List.of() : args;
            env = env == null ? Map.of() : env;
            headers = headers == null ? Map.of() : headers;
            scopes = scopes == null ? List.of() : scopes;
        }
    }
}
