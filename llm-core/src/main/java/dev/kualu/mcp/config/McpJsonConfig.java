package dev.kualu.mcp.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.mcp.McpException;
import dev.kualu.mcp.McpServerConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the ecosystem-standard {@code mcp.json} (Claude Desktop / VS Code shape):
 * a top-level {@code mcpServers} or {@code servers} map of named entries. Stdio
 * entries carry {@code command}/{@code args}/{@code env}; HTTP entries carry
 * {@code type:"http", url, headers}. Strings support {@code ${ENV}} and
 * {@code ${input:id}} placeholders (the latter reads {@code INPUT_ID}).
 */
public final class McpJsonConfig {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private McpJsonConfig() {
    }

    /**
     * Loads with the project environment: {@code .env} in the working directory,
     * overridden by real process environment variables (same precedence as
     * {@link dev.kualu.llm.client.LlmConfig}).
     */
    public static List<McpServerConfig> load(Path file) {
        Map<String, String> env = new LinkedHashMap<>(LlmConfig.loadDotEnv(Path.of(".env")));
        env.putAll(System.getenv());
        return load(file, env);
    }

    /** Reads and parses the file; a missing file yields an empty list. */
    public static List<McpServerConfig> load(Path file, Map<String, String> environment) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return parse(JsonParser.parseString(text).getAsJsonObject(), environment);
        } catch (IOException ex) {
            throw new McpException("cannot read mcp config " + file + ": " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            if (ex instanceof McpException mcp) {
                throw mcp;
            }
            throw new McpException("invalid mcp config " + file + ": " + ex.getMessage(), ex);
        }
    }

    /** Parses an already-read config document. */
    public static List<McpServerConfig> parse(JsonObject root, Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        JsonObject servers = null;
        for (String key : List.of("mcpServers", "servers")) {
            if (root.has(key) && root.get(key).isJsonObject()) {
                servers = root.getAsJsonObject(key);
                break;
            }
        }
        if (servers == null) {
            throw new McpException("mcp config has no 'mcpServers' or 'servers' object");
        }
        Map<String, McpServerConfig> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new McpException("mcp server '" + entry.getKey() + "' must be an object");
            }
            McpServerConfig config = parseServer(entry.getKey(), entry.getValue().getAsJsonObject(), env);
            parsed.put(config.name(), config);
        }
        return List.copyOf(parsed.values());
    }

    private static McpServerConfig parseServer(String name, JsonObject entry, Map<String, String> environment) {
        String type = entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString()
                : null;
        if ("sse".equalsIgnoreCase(type)) {
            throw new McpException("mcp server '" + name + "': legacy HTTP+SSE transport is not supported");
        }
        if ("http".equalsIgnoreCase(type) || (type == null && entry.has("url") && !entry.has("command"))) {
            return parseHttpServer(name, entry, environment);
        }
        if ("stdio".equalsIgnoreCase(type) || entry.has("command")) {
            return parseStdioServer(name, entry, environment);
        }
        throw new McpException("mcp server '" + name + "': cannot determine transport"
                + " (need 'command' for stdio or type 'http' with 'url')");
    }

    private static McpServerConfig parseStdioServer(String name, JsonObject entry, Map<String, String> environment) {
        if (!entry.has("command") || !entry.get("command").isJsonPrimitive()) {
            throw new McpException("mcp server '" + name + "' needs a 'command'");
        }
        List<String> argv = new ArrayList<>();
        argv.add(resolve(name, "command", entry.get("command").getAsString(), environment));
        if (entry.has("args") && entry.get("args").isJsonArray()) {
            for (JsonElement arg : entry.getAsJsonArray("args")) {
                if (!arg.isJsonPrimitive()) {
                    throw new McpException("mcp server '" + name + "': args must be strings");
                }
                argv.add(resolve(name, "args", arg.getAsString(), environment));
            }
        }
        return McpServerConfig.stdio(name, argv, parseEnv(name, entry, environment));
    }

    private static McpServerConfig parseHttpServer(String name, JsonObject entry, Map<String, String> environment) {
        if (!entry.has("url") || !entry.get("url").isJsonPrimitive()) {
            throw new McpException("mcp server '" + name + "' needs a 'url'");
        }
        String url = resolve(name, "url", entry.get("url").getAsString(), environment);
        Map<String, String> headers = new LinkedHashMap<>();
        if (entry.has("headers") && entry.get("headers").isJsonObject()) {
            for (Map.Entry<String, JsonElement> header : entry.getAsJsonObject("headers").entrySet()) {
                if (!header.getValue().isJsonPrimitive()) {
                    throw new McpException("mcp server '" + name + "': header values must be strings");
                }
                headers.put(header.getKey(), resolve(name, "headers", header.getValue().getAsString(), environment));
            }
        }
        McpServerConfig config = McpServerConfig.http(name, url, headers);
        if (entry.has("auth") && entry.get("auth").isJsonPrimitive()) {
            config = config.withAuthMode(parseAuthMode(name, entry.get("auth").getAsString()));
        }
        if (entry.has("client_id") && entry.get("client_id").isJsonPrimitive()) {
            config = config.withOAuthClient(
                    entry.get("client_id").getAsString(), parseScopes(name, entry));
        } else if (entry.has("scopes")) {
            config = config.withOAuthClient(null, parseScopes(name, entry));
        }
        return config;
    }

    private static List<String> parseScopes(String name, JsonObject entry) {
        if (!entry.has("scopes")) {
            return List.of();
        }
        if (!entry.get("scopes").isJsonArray()) {
            throw new McpException("mcp server '" + name + "': scopes must be an array of strings");
        }
        List<String> scopes = new ArrayList<>();
        for (JsonElement scope : entry.getAsJsonArray("scopes")) {
            if (!scope.isJsonPrimitive()) {
                throw new McpException("mcp server '" + name + "': scopes must be strings");
            }
            scopes.add(scope.getAsString());
        }
        return scopes;
    }

    private static McpServerConfig.AuthMode parseAuthMode(String name, String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "headers" -> McpServerConfig.AuthMode.HEADERS;
            case "oauth" -> McpServerConfig.AuthMode.OAUTH;
            default -> throw new McpException("mcp server '" + name + "': unknown auth mode '" + raw + "'");
        };
    }

    private static Map<String, String> parseEnv(String name, JsonObject entry, Map<String, String> environment) {
        Map<String, String> env = new LinkedHashMap<>();
        if (entry.has("env") && entry.get("env").isJsonObject()) {
            for (Map.Entry<String, JsonElement> var : entry.getAsJsonObject("env").entrySet()) {
                if (!var.getValue().isJsonPrimitive()) {
                    throw new McpException("mcp server '" + name + "': env values must be strings");
                }
                env.put(var.getKey(), resolve(name, "env", var.getValue().getAsString(), environment));
            }
        }
        return env;
    }

    /**
     * Resolves {@code ${ENV}} and {@code ${input:id}} (→ {@code INPUT_ID}) against
     * the environment; an unresolvable placeholder fails the load instead of
     * leaking the template to the wire.
     */
    static String resolve(String server, String field, String raw, Map<String, String> environment) {
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            String key;
            if (token.toLowerCase(Locale.ROOT).startsWith("input:")) {
                key = "INPUT_" + token.substring("input:".length()).toUpperCase(Locale.ROOT);
            } else {
                key = token;
            }
            String value = environment.get(key);
            if (value == null) {
                throw new McpException("mcp server '" + server + "': " + field
                        + " references environment variable '" + key + "', which is not set");
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}
