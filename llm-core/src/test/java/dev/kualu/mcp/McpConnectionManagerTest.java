package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kualu.llm.AgentTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConnectionManagerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void connectsAndExposesNamespacedTools() {
        AtomicReference<List<String>> toolNames = new AtomicReference<>(List.of("navigate"));
        List<FakeMcpTransport> created = new CopyOnWriteArrayList<>();
        try (McpConnectionManager manager = manager(created, toolNames,
                List.of(McpServerConfig.stdio("playwright", List.of("cmd"), null)))) {
            manager.start();
            List<AgentTool> tools = manager.tools();
            assertEquals(1, tools.size());
            AgentTool tool = tools.get(0);
            assertEquals("mcp_playwright_navigate", tool.name());
            assertTrue(tool.description().contains("(MCP tool: playwright/navigate)"));
        }
    }

    @Test
    void toolCallsRouteToTheServerSession() {
        List<FakeMcpTransport> created = new CopyOnWriteArrayList<>();
        try (McpConnectionManager manager = manager(created, new AtomicReference<>(List.of("echo")),
                List.of(McpServerConfig.stdio("demo", List.of("cmd"), null)))) {
            manager.start();
            String result = manager.tools().get(0).execute("{\"text\":\"hi\"}");
            assertTrue(result.contains("called:echo"));
        }
    }

    @Test
    void listChangedNotificationRefreshesTools() {
        AtomicReference<List<String>> toolNames = new AtomicReference<>(List.of("alpha"));
        List<FakeMcpTransport> created = new CopyOnWriteArrayList<>();
        try (McpConnectionManager manager = manager(created, toolNames,
                List.of(McpServerConfig.stdio("demo", List.of("cmd"), null)))) {
            manager.start();
            assertEquals(List.of("mcp_demo_alpha"), names(manager.tools()));

            toolNames.set(List.of("beta"));
            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "notifications/tools/list_changed");
            created.get(0).deliver(notification);

            assertEquals(List.of("mcp_demo_beta"), names(manager.tools()));
        }
    }

    @Test
    void droppedConnectionRemovesToolsUntilReconnect() throws Exception {
        AtomicReference<List<String>> toolNames = new AtomicReference<>(List.of("t"));
        List<FakeMcpTransport> created = new CopyOnWriteArrayList<>();
        try (McpConnectionManager manager = manager(created, toolNames,
                List.of(McpServerConfig.stdio("demo", List.of("cmd"), null)))) {
            manager.start();
            assertEquals(1, manager.tools().size());

            created.get(0).disconnect();
            assertEquals(0, manager.tools().size());

            // first retry fires after 1s of backoff and rebuilds the session
            Thread.sleep(1500);
            assertEquals(List.of("mcp_demo_t"), names(manager.tools()));
        }
    }

    @Test
    void disabledServersAreSkipped() {
        List<FakeMcpTransport> created = new CopyOnWriteArrayList<>();
        McpServerConfig disabled = McpServerConfig.stdio("off", List.of("cmd"), null).withEnabled(false);
        try (McpConnectionManager manager = manager(created, new AtomicReference<>(List.of()), List.of(disabled))) {
            manager.start();
            assertTrue(manager.tools().isEmpty());
            assertTrue(created.isEmpty());
        }
    }

    @Test
    void duplicateServerNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new McpConnectionManager(
                List.of(McpServerConfig.stdio("demo", List.of("cmd"), null),
                        McpServerConfig.stdio("demo", List.of("cmd"), null))));
    }

    @Test
    void backoffGrowsAndIsCapped() {
        assertEquals(Duration.ofSeconds(1), McpConnectionManager.backoff(1));
        assertEquals(Duration.ofSeconds(2), McpConnectionManager.backoff(2));
        assertEquals(Duration.ofSeconds(4), McpConnectionManager.backoff(3));
        assertEquals(Duration.ofMinutes(5), McpConnectionManager.backoff(50));
    }

    private static McpConnectionManager manager(
            List<FakeMcpTransport> created,
            AtomicReference<List<String>> toolNames,
            List<McpServerConfig> servers) {
        Function<McpServerConfig, McpTransport> factory = config -> {
            FakeMcpTransport transport = new FakeMcpTransport();
            transport.responder = script(toolNames);
            created.add(transport);
            return transport;
        };
        return new McpConnectionManager(
                servers, TIMEOUT, TIMEOUT, 1000, Duration.ZERO, factory, null);
    }

    private static Function<JsonObject, JsonObject> script(AtomicReference<List<String>> toolNames) {
        return message -> {
            if (!message.has("id")) {
                return null;
            }
            String method = message.get("method").getAsString();
            JsonObject result = new JsonObject();
            switch (method) {
                case "initialize" -> {
                    result.addProperty("protocolVersion", "2025-06-18");
                    result.add("capabilities", new JsonObject());
                    JsonObject info = new JsonObject();
                    info.addProperty("name", "scripted");
                    result.add("serverInfo", info);
                }
                case "tools/list" -> {
                    JsonArray tools = new JsonArray();
                    for (String name : toolNames.get()) {
                        JsonObject tool = new JsonObject();
                        tool.addProperty("name", name);
                        tool.addProperty("description", "tool " + name);
                        tool.add("inputSchema", new JsonObject());
                        tools.add(tool);
                    }
                    result.add("tools", tools);
                }
                case "tools/call" -> {
                    JsonArray content = new JsonArray();
                    JsonObject block = new JsonObject();
                    block.addProperty("type", "text");
                    block.addProperty("text", "called:" + message.getAsJsonObject("params").get("name").getAsString());
                    content.add(block);
                    result.add("content", content);
                }
                default -> {
                    // empty result
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", message.get("id").getAsLong());
            response.add("result", result);
            return response;
        };
    }

    private static List<String> names(List<AgentTool> tools) {
        List<String> names = new ArrayList<>();
        for (AgentTool tool : tools) {
            names.add(tool.name());
        }
        return names;
    }
}
