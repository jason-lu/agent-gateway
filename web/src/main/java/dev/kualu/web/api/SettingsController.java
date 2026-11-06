package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.McpServerResponse;
import dev.kualu.web.mcp.UserMcpRegistry;
import dev.kualu.web.mcp.UserMcpServer;
import dev.kualu.web.mcp.UserMcpServerStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The caller's own MCP server settings. Entries carry personal credentials, so every
 * operation is scoped to the authenticated owner and secrets are masked in responses:
 * a {@link #MASK} value in an update means "keep what is stored", an empty string
 * removes the key, anything else replaces it.
 */
@RestController
@RequestMapping("/api/v1/settings/mcp-servers")
public class SettingsController {

    /** Placeholder returned in place of real env/header values. */
    public static final String MASK = "••••••••";

    private final UserMcpServerStore store;
    private final ObjectProvider<UserMcpRegistry> registries;

    public SettingsController(UserMcpServerStore store, ObjectProvider<UserMcpRegistry> registries) {
        this.store = store;
        this.registries = registries;
    }

    @GetMapping
    public List<McpServerResponse> list(Authentication authentication) {
        String owner = authentication.getName();
        Map<String, Boolean> states = connectionStates(owner);
        return store.listByOwner(owner).stream()
                .map(server -> toResponse(server, statusOf(server, states)))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public McpServerResponse create(Authentication authentication, @Valid @RequestBody McpServerBody body) {
        String owner = authentication.getName();
        UserMcpServer server = toServer(body, owner, null);
        if (store.find(owner, server.name()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "server '" + server.name() + "' already exists");
        }
        store.save(owner, server);
        invalidate(owner);
        return toResponse(server, "unknown");
    }

    @PutMapping("/{name}")
    public McpServerResponse update(
            Authentication authentication, @PathVariable String name, @Valid @RequestBody McpServerBody body) {
        String owner = authentication.getName();
        UserMcpServer existing = store.find(owner, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such server: " + name));
        UserMcpServer server = toServer(body, owner, existing);
        store.save(owner, server);
        invalidate(owner);
        return toResponse(server, "unknown");
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable String name) {
        String owner = authentication.getName();
        if (!store.delete(owner, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such server: " + name);
        }
        invalidate(owner);
    }

    /**
     * @param existing the stored row on update ({@code null} on create), used to resolve
     *                 {@link #MASK} values and to drop removed keys
     */
    private UserMcpServer toServer(McpServerBody body, String owner, UserMcpServer existing) {
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
        }
        if (existing != null && !body.name().equals(existing.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name cannot be changed; delete and recreate");
        }
        UserMcpServer server = new UserMcpServer(
                body.name(),
                body.transport(),
                body.command(),
                body.args(),
                mergeSecrets(body.env(), existing == null ? Map.of() : existing.env()),
                body.url(),
                mergeSecrets(body.headers(), existing == null ? Map.of() : existing.headers()),
                body.enabled());
        try {
            server.validate();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        return server;
    }

    /**
     * Resolves incoming secret values against the stored map: {@link #MASK} keeps the
     * stored value, an empty string drops the key, anything else is the new value.
     */
    private static Map<String, String> mergeSecrets(Map<String, String> incoming, Map<String, String> stored) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : incoming.entrySet().stream().toList()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "env/header keys must not be blank");
            }
            if (MASK.equals(value)) {
                String previous = stored.get(key);
                if (previous == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "cannot keep a masked value that was never stored: " + key);
                }
                merged.put(key, previous);
            } else if (value != null && !value.isEmpty()) {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private Map<String, Boolean> connectionStates(String owner) {
        UserMcpRegistry registry = registries.getIfAvailable();
        return registry == null ? Map.of() : registry.connectionStates(owner);
    }

    private void invalidate(String owner) {
        UserMcpRegistry registry = registries.getIfAvailable();
        if (registry != null) {
            registry.invalidate(owner);
        }
    }

    private static String statusOf(UserMcpServer server, Map<String, Boolean> states) {
        if (!server.enabled()) {
            return "disabled";
        }
        Boolean connected = states.get(server.name());
        return connected == null ? "unknown" : connected ? "connected" : "disconnected";
    }

    private static McpServerResponse toResponse(UserMcpServer server, String status) {
        return new McpServerResponse(
                server.name(),
                server.transport(),
                server.command(),
                server.args(),
                mask(server.env()),
                server.url(),
                mask(server.headers()),
                server.enabled(),
                status);
    }

    private static Map<String, String> mask(Map<String, String> values) {
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet().stream().toList()) {
            masked.put(entry.getKey(), entry.getValue() == null || entry.getValue().isEmpty() ? "" : MASK);
        }
        return masked;
    }

    public record McpServerBody(
            @NotBlank String name,
            @NotBlank String transport,
            String command,
            List<String> args,
            @NotNull Map<String, String> env,
            String url,
            @NotNull Map<String, String> headers,
            @NotNull Boolean enabled) {
    }
}
