package dev.kualu.mcp.auth;

import java.util.Optional;

/**
 * Persists OAuth tokens per MCP server. Refresh tokens must survive restarts,
 * otherwise every restart needs a fresh interactive login.
 */
public interface McpTokenStore {

    Optional<StoredTokens> load(String serverName);

    void save(String serverName, StoredTokens tokens);

    default void delete(String serverName) {
    }
}
