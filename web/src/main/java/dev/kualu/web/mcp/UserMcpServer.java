package dev.kualu.web.mcp;

import dev.kualu.mcp.McpServerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One user-configured MCP server. Mirrors the {@code mcp.json} entry shapes, but
 * belongs to a single owner: credentials in {@code env}/{@code headers} are personal
 * and must never surface to other users.
 */
public record UserMcpServer(
        String name,
        String transport,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        boolean enabled) {

    /** Matches the {@code mcp_<server>_<tool>} cleaning charset, so names stay stable. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";

    public UserMcpServer {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transport, "transport");
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Copy with the enabled flag set. */
    public UserMcpServer withEnabled(boolean flag) {
        return new UserMcpServer(name, transport, command, args, env, url, headers, flag);
    }

    /** @throws IllegalArgumentException with a message fit for the API layer */
    public void validate() {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name must match [A-Za-z0-9_-]{1,64} (was '" + name + "')");
        }
        if (TRANSPORT_STDIO.equals(transport)) {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("stdio server '" + name + "' needs a command");
            }
        } else if (TRANSPORT_HTTP.equals(transport)) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("http server '" + name + "' needs a url");
            }
        } else {
            throw new IllegalArgumentException("transport must be 'stdio' or 'http' (was '" + transport + "')");
        }
    }

    /**
     * Connection config for the llm-core manager. User-defined HTTP servers always use
     * static headers — the OAuth flow opens a browser on the host and is an
     * operator-level concern, so it stays in the global {@code mcp.json} layer.
     */
    public McpServerConfig toMcpServerConfig() {
        McpServerConfig config;
        if (TRANSPORT_STDIO.equals(transport)) {
            List<String> argv = new ArrayList<>(args.size() + 1);
            argv.add(command);
            argv.addAll(args);
            config = McpServerConfig.stdio(name, argv, env);
        } else {
            config = McpServerConfig.http(name, url, headers).withAuthMode(McpServerConfig.AuthMode.HEADERS);
        }
        return config.withEnabled(enabled);
    }
}
