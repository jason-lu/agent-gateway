package dev.kualu.mcp.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kualu.mcp.McpException;
import dev.kualu.mcp.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpJsonConfigTest {

    private static final Map<String, String> ENV = Map.of(
            "GITHUB_PAT", "secret-token",
            "INPUT_GITHUB_MCP_PAT", "input-token");

    @Test
    void parsesStdioEntryWithArgsAndEnv() {
        List<McpServerConfig> servers = parse("""
                {"mcpServers": {"playwright": {
                  "command": "npx",
                  "args": ["@playwright/mcp@latest"],
                  "env": {"PLAYWRIGHT_BROWSER": "chromium"}
                }}}""");
        McpServerConfig server = servers.get(0);
        assertEquals("playwright", server.name());
        assertEquals(McpServerConfig.Transport.STDIO, server.transport());
        assertEquals(List.of("npx", "@playwright/mcp@latest"), server.command());
        assertEquals(Map.of("PLAYWRIGHT_BROWSER", "chromium"), server.env());
    }

    @Test
    void parsesHttpEntryUnderServersKey() {
        List<McpServerConfig> servers = parse("""
                {"servers": {"github": {
                  "type": "http",
                  "url": "https://api.githubcopilot.com/mcp/",
                  "headers": {"Authorization": "Bearer ${GITHUB_PAT}"}
                }}}""");
        McpServerConfig server = servers.get(0);
        assertEquals(McpServerConfig.Transport.HTTP, server.transport());
        assertEquals("https://api.githubcopilot.com/mcp/", server.url());
        assertEquals("Bearer secret-token", server.headers().get("Authorization"));
    }

    @Test
    void urlWithoutCommandIsHttp_commandWithoutTypeIsStdio() {
        List<McpServerConfig> servers = parse("""
                {"mcpServers": {
                  "a": {"url": "https://example.com/mcp"},
                  "b": {"command": "run-me"}
                }}""");
        assertEquals(McpServerConfig.Transport.HTTP, servers.get(0).transport());
        assertEquals(McpServerConfig.Transport.STDIO, servers.get(1).transport());
    }

    @Test
    void legacySseIsRejected() {
        McpException ex = assertThrows(McpException.class, () -> parse("""
                {"mcpServers": {"old": {"type": "sse", "url": "https://example.com/sse"}}}"""));
        assertTrue(ex.getMessage().contains("SSE"));
    }

    @Test
    void missingPlaceholderFailsTheLoad() {
        McpException ex = assertThrows(McpException.class, () -> parse("""
                {"mcpServers": {"x": {"command": "c", "env": {"TOKEN": "${NOT_SET_ANYWHERE}"}}}}"""));
        assertTrue(ex.getMessage().contains("NOT_SET_ANYWHERE"));
    }

    @Test
    void inputPlaceholderMapsToUpperCaseEnv() {
        List<McpServerConfig> servers = parse("""
                {"mcpServers": {"github": {
                  "type": "http", "url": "https://example.com/mcp",
                  "headers": {"Authorization": "Bearer ${input:github_mcp_pat}"}
                }}}""");
        assertEquals("Bearer input-token", servers.get(0).headers().get("Authorization"));
    }

    @Test
    void documentWithoutServerMapIsRejected() {
        assertThrows(McpException.class, () -> parse("{\"other\": {}}"));
    }

    @Test
    void authModeIsParsed() {
        List<McpServerConfig> servers = parse("""
                {"mcpServers": {
                  "with-token": {"type": "http", "url": "https://a.example/mcp", "auth": "headers",
                                 "headers": {"Authorization": "t"}},
                  "with-oauth": {"type": "http", "url": "https://b.example/mcp", "auth": "oauth"}
                }}""");
        assertEquals(McpServerConfig.AuthMode.HEADERS, servers.get(0).authMode());
        assertEquals(McpServerConfig.AuthMode.OAUTH, servers.get(1).authMode());
    }

    @Test
    void clientIdAndScopesAreParsed() {
        List<McpServerConfig> servers = parse("""
                {"mcpServers": {"github": {
                  "type": "http",
                  "url": "https://api.githubcopilot.com/mcp/",
                  "client_id": "Iv1.my-app",
                  "scopes": ["repo", "read:org"]
                }}}""");
        McpServerConfig server = servers.get(0);
        assertEquals("Iv1.my-app", server.clientId());
        assertEquals(List.of("repo", "read:org"), server.scopes());
    }

    @Test
    void entriesWithoutCommandOrUrlAreRejected() {
        assertThrows(McpException.class, () -> parse("""
                {"mcpServers": {"mystery": {"type": "websocket", "url": "wss://x"}}}"""));
    }

    @Test
    void missingFileYieldsEmptyList(@TempDir Path dir) {
        assertTrue(McpJsonConfig.load(dir.resolve("absent.json"), ENV).isEmpty());
    }

    @Test
    void fileLoadParsesDocument(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mcp.json");
        java.nio.file.Files.writeString(file, """
                {"mcpServers": {"local": {"command": "echo"}}}""");
        List<McpServerConfig> servers = McpJsonConfig.load(file, ENV);
        assertEquals("local", servers.get(0).name());
    }

    private static List<McpServerConfig> parse(String json) {
        return McpJsonConfig.parse(JsonParser.parseString(json).getAsJsonObject(), ENV);
    }
}
