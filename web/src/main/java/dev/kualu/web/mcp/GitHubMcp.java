package dev.kualu.web.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The per-user GitHub MCP server derived from a user's bound {@code GH_TOKEN}
 * (or {@code GITHUB_TOKEN}) in the tool-credential store. This replaces the
 * retired shared {@code mcp.json} github server: every user talks to GitHub with
 * their own identity, and no one gets GitHub tools without binding a token.
 */
public final class GitHubMcp {

    /** Same name the shared server used, so tool names stay {@code mcp_github_*}. */
    public static final String SERVER_NAME = "github";

    public static final String URL = "https://api.githubcopilot.com/mcp/";

    private GitHubMcp() {
    }

    /**
     * @param userEnv the owner's tool-credential entries
     * @param definedNames every server name the owner configures themselves
     *                     (enabled or not — a disabled personal {@code github}
     *                     stays disabled instead of being silently replaced)
     * @return the synthetic server, or empty when the owner binds no token or
     *         defines their own {@code github} server
     */
    public static Optional<UserMcpServer> synthesize(Map<String, String> userEnv, Set<String> definedNames) {
        if (definedNames.contains(SERVER_NAME)) {
            return Optional.empty();
        }
        String token = userEnv.get("GH_TOKEN");
        if (token == null || token.isBlank()) {
            token = userEnv.get("GITHUB_TOKEN");
        }
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new UserMcpServer(
                SERVER_NAME,
                UserMcpServer.TRANSPORT_HTTP,
                null,
                List.of(),
                Map.of(),
                URL,
                Map.of("Authorization", "Bearer " + token),
                true));
    }
}
