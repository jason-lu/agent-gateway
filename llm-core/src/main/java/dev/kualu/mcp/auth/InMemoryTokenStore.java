package dev.kualu.mcp.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local token storage; tokens do not survive a restart. */
public final class InMemoryTokenStore implements McpTokenStore {

    private final Map<String, StoredTokens> tokens = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredTokens> load(String serverName) {
        return Optional.ofNullable(tokens.get(serverName));
    }

    @Override
    public void save(String serverName, StoredTokens token) {
        tokens.put(serverName, token);
    }

    @Override
    public void delete(String serverName) {
        tokens.remove(serverName);
    }
}
