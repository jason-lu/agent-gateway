package dev.kualu.web.mcp;

import dev.kualu.web.config.McpProperties;
import dev.kualu.web.tools.InMemoryUserToolEnvStore;
import dev.kualu.web.tools.UserToolEnvStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Composition of stored servers plus the synthesized GitHub one — no network. */
class UserMcpRegistryTest {

    private static UserMcpServerStore storeWith(UserMcpServer... servers) {
        InMemoryUserMcpServerStore store = new InMemoryUserMcpServerStore();
        for (UserMcpServer server : servers) {
            store.save("ada", server);
        }
        return store;
    }

    private static UserToolEnvStore toolEnvWith(String key, String value) {
        InMemoryUserToolEnvStore store = new InMemoryUserToolEnvStore();
        store.save("ada", key, value);
        return store;
    }

    private static UserMcpRegistry registry(UserMcpServerStore store, UserToolEnvStore toolEnv) {
        return new UserMcpRegistry(store, toolEnv,
                new McpProperties(true, "./mcp.json", 60, 8000, 300, List.of()));
    }

    @Test
    void aTokenWithoutPersonalServersAddsTheSynthesizedGitHub() {
        try (UserMcpRegistry registry = registry(storeWith(), toolEnvWith("GH_TOKEN", "ghp_abc"))) {
            List<UserMcpServer> config = registry.effectiveConfig("ada");

            assertEquals(1, config.size());
            assertEquals("github", config.get(0).name());
            assertEquals("Bearer ghp_abc", config.get(0).headers().get("Authorization"));
        }
    }

    @Test
    void aPersonalGitHubServerSuppressesTheSynthesizedOne() {
        UserMcpServer mine = new UserMcpServer(
                "github", UserMcpServer.TRANSPORT_HTTP, null, List.of(), java.util.Map.of(),
                "https://example.invalid/mcp", java.util.Map.of("Authorization", "Bearer mine"), true);
        try (UserMcpRegistry registry = registry(storeWith(mine), toolEnvWith("GH_TOKEN", "ghp_abc"))) {
            List<UserMcpServer> config = registry.effectiveConfig("ada");

            assertEquals(List.of(mine), config);
        }
    }

    @Test
    void noTokenMeansNoGitHubToolsAtAll() {
        try (UserMcpRegistry registry = registry(storeWith(), toolEnvWith("NPM_TOKEN", "n"))) {
            assertTrue(registry.effectiveConfig("ada").isEmpty());
        }
    }

    @Test
    void tokenRotationChangesTheEffectiveConfig() {
        UserToolEnvStore toolEnv = toolEnvWith("GH_TOKEN", "old");
        try (UserMcpRegistry registry = registry(storeWith(), toolEnv)) {
            List<UserMcpServer> before = registry.effectiveConfig("ada");
            toolEnv.save("ada", "GH_TOKEN", "new");
            List<UserMcpServer> after = registry.effectiveConfig("ada");

            assertEquals(1, before.size());
            assertEquals(1, after.size());
            assertEquals("Bearer old", before.get(0).headers().get("Authorization"));
            assertEquals("Bearer new", after.get(0).headers().get("Authorization"));
        }
    }
}
