package dev.kualu.web.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubMcpTest {

    @Test
    void noTokenYieldsNoServer() {
        assertTrue(GitHubMcp.synthesize(Map.of(), Set.of()).isEmpty());
    }

    @Test
    void blankTokensYieldNoServer() {
        assertTrue(GitHubMcp.synthesize(Map.of("GH_TOKEN", " ", "GITHUB_TOKEN", ""), Set.of()).isEmpty());
    }

    @Test
    void ghTokenBecomesABearerHeader() {
        UserMcpServer server = GitHubMcp.synthesize(Map.of("GH_TOKEN", "ghp_abc"), Set.of()).orElseThrow();

        assertEquals("github", server.name());
        assertEquals(UserMcpServer.TRANSPORT_HTTP, server.transport());
        assertEquals(GitHubMcp.URL, server.url());
        assertEquals(Map.of("Authorization", "Bearer ghp_abc"), server.headers());
        assertTrue(server.enabled());
    }

    @Test
    void githubTokenIsTheFallbackName() {
        UserMcpServer server = GitHubMcp.synthesize(Map.of("GITHUB_TOKEN", "ghp_alt"), Set.of()).orElseThrow();

        assertEquals(Map.of("Authorization", "Bearer ghp_alt"), server.headers());
    }

    @Test
    void ghTokenWinsOverGithubToken() {
        UserMcpServer server = GitHubMcp.synthesize(
                Map.of("GH_TOKEN", "ghp_one", "GITHUB_TOKEN", "ghp_two"), Set.of()).orElseThrow();

        assertEquals(Map.of("Authorization", "Bearer ghp_one"), server.headers());
    }

    @Test
    void aUserDefinedGithubServerIsNeverReplaced() {
        assertTrue(GitHubMcp.synthesize(Map.of("GH_TOKEN", "ghp_abc"), Set.of("github")).isEmpty());
    }

    @Test
    void synthesizedServersTrackTokenRotation() {
        UserMcpServer before = GitHubMcp.synthesize(Map.of("GH_TOKEN", "old"), Set.of()).orElseThrow();
        UserMcpServer after = GitHubMcp.synthesize(Map.of("GH_TOKEN", "new"), Set.of()).orElseThrow();

        assertFalse(before.equals(after));
    }
}
