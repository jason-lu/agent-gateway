package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSessionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void openPerformsHandshakeAndSendsInitializedNotification() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.responder = message -> switch (message.get("method").getAsString()) {
            case "initialize" -> {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", "2025-06-18");
                result.add("capabilities", new JsonObject());
                JsonObject info = new JsonObject();
                info.addProperty("name", "demo-server");
                result.add("serverInfo", info);
                yield response(message, result);
            }
            default -> null;
        };

        McpSession session = new McpSession(transport);
        session.open(TIMEOUT);

        assertEquals("2025-06-18", session.protocolVersion());
        assertEquals("demo-server", session.serverName());
        assertTrue(session.isOpen());

        JsonObject init = transport.sentAt(0);
        assertEquals("initialize", init.get("method").getAsString());
        JsonObject params = init.getAsJsonObject("params");
        assertEquals("2025-06-18", params.get("protocolVersion").getAsString());
        assertEquals(McpSession.CLIENT_NAME,
                params.getAsJsonObject("clientInfo").get("name").getAsString());

        JsonObject initialized = transport.sentAt(1);
        assertEquals("notifications/initialized", initialized.get("method").getAsString());
        assertFalse(initialized.has("id"));
    }

    @Test
    void openRejectsUnsupportedProtocolVersion() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.responder = message -> {
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", "2020-01-01");
            return response(message, result);
        };
        McpSession session = new McpSession(transport);
        assertThrows(McpException.class, () -> session.open(TIMEOUT));
        assertFalse(session.isOpen());
    }

    @Test
    void requestReturnsResultObject() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.responder = message -> {
            JsonObject result = new JsonObject();
            result.addProperty("value", 42);
            return response(message, result);
        };
        McpSession session = opened(transport);
        JsonObject result = session.request("tools/list", new JsonObject(), TIMEOUT);
        assertEquals(42, result.get("value").getAsInt());
    }

    @Test
    void requestSurfacesJsonRpcError() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.responder = message -> message.has("id")
                ? JsonRpc.error(JsonRpc.id(message), -32601, "no such method")
                : null;
        McpSession session = opened(transport);
        McpException ex = assertThrows(McpException.class,
                () -> session.request("bogus", new JsonObject(), TIMEOUT));
        assertTrue(ex.getMessage().contains("-32601"));
        assertTrue(ex.getMessage().contains("no such method"));
    }

    @Test
    void requestTimesOutWhenNoResponseArrives() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpSession session = new McpSession(transport);
        McpException ex = assertThrows(McpException.class,
                () -> session.request("tools/list", new JsonObject(), Duration.ofMillis(50)));
        assertTrue(ex.getMessage().contains("timed out"));
    }

    @Test
    void listToolsFollowsPaginationCursor() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.responder = message -> {
            JsonObject params = message.getAsJsonObject("params");
            JsonObject result = new JsonObject();
            JsonArray tools = new JsonArray();
            JsonObject tool = new JsonObject();
            if (params != null && params.has("cursor")) {
                tool.addProperty("name", "second");
            } else {
                tool.addProperty("name", "first");
                result.addProperty("nextCursor", "page-2");
            }
            tool.add("inputSchema", new JsonObject());
            tools.add(tool);
            result.add("tools", tools);
            return response(message, result);
        };
        McpSession session = opened(transport);
        List<McpRemoteTool> tools = session.listTools(TIMEOUT);
        assertEquals(List.of("first", "second"), tools.stream().map(McpRemoteTool::name).toList());
    }

    @Test
    void callToolMapsContentErrorFlagAndStructuredContent() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.responder = message -> {
            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", "hello");
            content.add(block);
            result.add("content", content);
            result.addProperty("isError", true);
            JsonObject structured = new JsonObject();
            structured.addProperty("n", 7);
            result.add("structuredContent", structured);
            return response(message, result);
        };
        McpSession session = opened(transport);
        McpCallResult result = session.callTool("echo", new JsonObject(), TIMEOUT);
        assertTrue(result.error());
        assertEquals(1, result.content().size());
        assertEquals("hello", result.content().get(0).getAsJsonObject().get("text").getAsString());
        assertNotNull(result.structuredContent());
        assertEquals(7, result.structuredContent().get("n").getAsInt());
    }

    @Test
    void notificationsReachRegisteredHandlers() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpSession session = opened(transport);
        AtomicReference<String> seen = new AtomicReference<>();
        session.onNotification("notifications/tools/list_changed", params -> seen.set("changed"));
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "notifications/tools/list_changed");
        transport.deliver(notification);
        assertEquals("changed", seen.get());
    }

    @Test
    void serverInitiatedRequestIsRejected() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpSession session = opened(transport);
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 99);
        request.addProperty("method", "sampling/createMessage");
        transport.deliver(request);
        JsonObject last = transport.sentAt(transport.sent.size() - 1);
        assertEquals(-32601, last.getAsJsonObject("error").get("code").getAsInt());
        assertEquals(99, last.get("id").getAsLong());
    }

    @Test
    void requestAfterCloseFails() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpSession session = new McpSession(transport);
        session.close();
        assertThrows(McpException.class, () -> session.request("ping", null, TIMEOUT));
    }

    private static JsonObject response(JsonObject request, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", JsonRpc.id(request));
        response.add("result", result);
        return response;
    }

    /** Opens a session on the fake transport, answering {@code initialize} first. */
    private static McpSession opened(FakeMcpTransport transport) {
        Function<JsonObject, JsonObject> delegate = transport.responder;
        transport.responder = message -> {
            if ("initialize".equals(message.get("method").getAsString())) {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", "2025-06-18");
                result.add("capabilities", new JsonObject());
                JsonObject info = new JsonObject();
                info.addProperty("name", "test-server");
                result.add("serverInfo", info);
                return response(message, result);
            }
            return delegate.apply(message);
        };
        McpSession session = new McpSession(transport);
        session.open(TIMEOUT);
        return session;
    }
}
