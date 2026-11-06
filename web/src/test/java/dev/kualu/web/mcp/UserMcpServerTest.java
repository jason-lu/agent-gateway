package dev.kualu.web.mcp;

import dev.kualu.mcp.McpServerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMcpServerTest {

    @Test
    void httpServersUseStaticHeadersNeverOAuth() {
        UserMcpServer server = new UserMcpServer(
                "github", UserMcpServer.TRANSPORT_HTTP, null, List.of(), Map.of(),
                "https://example.com/mcp", Map.of("Authorization", "Bearer t"), true);
        server.validate();
        McpServerConfig config = server.toMcpServerConfig();
        assertThat(config.transport()).isEqualTo(McpServerConfig.Transport.HTTP);
        assertThat(config.authMode()).isEqualTo(McpServerConfig.AuthMode.HEADERS);
        assertThat(config.enabled()).isTrue();
    }

    @Test
    void stdioCommandBecomesArgv() {
        UserMcpServer server = new UserMcpServer(
                "playwright", UserMcpServer.TRANSPORT_STDIO, "npx", List.of("-y", "@playwright/mcp"),
                Map.of("TOKEN", "t"), null, Map.of(), false);
        server.validate();
        McpServerConfig config = server.toMcpServerConfig();
        assertThat(config.transport()).isEqualTo(McpServerConfig.Transport.STDIO);
        assertThat(config.command()).containsExactly("npx", "-y", "@playwright/mcp");
        assertThat(config.enabled()).isFalse();
    }

    @Test
    void validateRejectsBadShapes() {
        assertThatThrownBy(() -> new UserMcpServer(
                "bad name", UserMcpServer.TRANSPORT_HTTP, null, List.of(), Map.of(),
                "https://example.com", Map.of(), true).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserMcpServer(
                "ok", UserMcpServer.TRANSPORT_HTTP, null, List.of(), Map.of(),
                " ", Map.of(), true).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserMcpServer(
                "ok", UserMcpServer.TRANSPORT_STDIO, " ", List.of(), Map.of(),
                null, Map.of(), true).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserMcpServer(
                "ok", "grpc", "cmd", List.of(), Map.of(), null, Map.of(), true).validate())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
