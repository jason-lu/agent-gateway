package dev.kualu.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration of one MCP server connection: a local stdio process or a remote
 * Streamable HTTP endpoint.
 */
public record McpServerConfig(
        String name,
        Transport transport,
        List<String> command,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        AuthMode authMode,
        String clientId,
        List<String> scopes,
        Duration callTimeout,
        boolean enabled) {

    public enum Transport {
        STDIO,
        HTTP
    }

    /**
     * Credential strategy for HTTP servers. {@code AUTO} picks static headers when
     * any are configured and OAuth otherwise; {@code HEADERS}/{@code OAUTH} force it.
     */
    public enum AuthMode {
        AUTO,
        HEADERS,
        OAUTH
    }

    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("server name must not be blank");
        }
        Objects.requireNonNull(transport, "transport");
        if (transport == Transport.STDIO && (command == null || command.isEmpty())) {
            throw new IllegalArgumentException("stdio server '" + name + "' needs a command");
        }
        if (transport == Transport.HTTP && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("http server '" + name + "' needs a url");
        }
        command = command == null ? List.of() : List.copyOf(command);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        authMode = authMode == null ? AuthMode.AUTO : authMode;
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /** Local stdio server (e.g. {@code ["npx", "@playwright/mcp@latest"]}). */
    public static McpServerConfig stdio(String name, List<String> command, Map<String, String> env) {
        return new McpServerConfig(name, Transport.STDIO, command, env, null, null, null, null, null, null, true);
    }

    /** Remote Streamable HTTP server with optional static credential headers. */
    public static McpServerConfig http(String name, String url, Map<String, String> headers) {
        return new McpServerConfig(name, Transport.HTTP, null, null, url, headers, null, null, null, null, true);
    }

    /** Copy with the auth mode set. */
    public McpServerConfig withAuthMode(AuthMode mode) {
        return new McpServerConfig(name, transport, command, env, url, headers, mode, clientId, scopes, callTimeout, enabled);
    }

    /** Copy with OAuth client registration overridden (own GitHub App / OAuth App). */
    public McpServerConfig withOAuthClient(String clientId, List<String> scopes) {
        return new McpServerConfig(name, transport, command, env, url, headers, authMode, clientId, scopes, callTimeout, enabled);
    }

    /** Copy with the call timeout set ({@code null} inherits the manager default). */
    public McpServerConfig withCallTimeout(Duration timeout) {
        return new McpServerConfig(name, transport, command, env, url, headers, authMode, clientId, scopes, timeout, enabled);
    }

    /** Copy with the enabled flag set. */
    public McpServerConfig withEnabled(boolean flag) {
        return new McpServerConfig(name, transport, command, env, url, headers, authMode, clientId, scopes, callTimeout, flag);
    }

    /** Lower-cased transport label for log lines. */
    public String transportLabel() {
        return transport.name().toLowerCase(Locale.ROOT);
    }
}
