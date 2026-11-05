package dev.kualu.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.kualu.mcp.auth.McpAuth;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamableHttpTransportTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void jsonResponseRoundTripAndSessionIdAttached() throws IOException {
        AtomicReference<String> secondRequestSession = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            JsonObject request = readRequest(exchange);
            if (guardNotification(exchange, request)) {
                return;
            }
            int call = calls.incrementAndGet();
            if (call == 2) {
                secondRequestSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            }
            JsonObject result = switch (request.get("method").getAsString()) {
                case "initialize" -> {
                    JsonObject init = new JsonObject();
                    init.addProperty("protocolVersion", "2025-06-18");
                    init.add("capabilities", new JsonObject());
                    JsonObject info = new JsonObject();
                    info.addProperty("name", "http-demo");
                    init.add("serverInfo", info);
                    yield init;
                }
                default -> {
                    JsonObject ok = new JsonObject();
                    ok.addProperty("ok", true);
                    yield ok;
                }
            };
            respond(exchange, 200, "application/json", jsonRpcResponse(request, result).toString(),
                    "Mcp-Session-Id", "sess-42");
        });

        try (McpSession session = new McpSession(new StreamableHttpTransport(url(server), McpAuth.none()))) {
            session.open(TIMEOUT);
            assertEquals("http-demo", session.serverName());
            session.request("tools/list", new JsonObject(), TIMEOUT);
            assertEquals("sess-42", secondRequestSession.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sseResponseDeliversMessages() throws IOException {
        HttpServer server = server(exchange -> {
            JsonObject request = readRequest(exchange);
            if (guardNotification(exchange, request)) {
                return;
            }
            JsonObject result = new JsonObject();
            if ("initialize".equals(request.get("method").getAsString())) {
                result.addProperty("protocolVersion", "2025-06-18");
                result.add("capabilities", new JsonObject());
                JsonObject info = new JsonObject();
                info.addProperty("name", "sse-demo");
                result.add("serverInfo", info);
            } else {
                result.addProperty("value", 7);
            }
            respond(exchange, 200, "text/event-stream",
                    "event: message\ndata: " + jsonRpcResponse(request, result) + "\n\n", null, null);
        });
        try (McpSession session = new McpSession(new StreamableHttpTransport(url(server), McpAuth.none()))) {
            session.open(TIMEOUT);
            JsonObject result = session.request("tools/list", new JsonObject(), TIMEOUT);
            assertEquals(7, result.get("value").getAsInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void notificationsGetAcceptedWithoutBody() throws IOException {
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = server(exchange -> {
            readRequest(exchange);
            posts.incrementAndGet();
            respond(exchange, 202, null, "", null, null);
        });
        try (StreamableHttpTransport transport =
                     new StreamableHttpTransport(url(server), McpAuth.none())) {
            transport.start(message -> {
            }, () -> {
            });
            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "notifications/initialized");
            transport.send(notification);
            assertEquals(1, posts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sessionExpiredTriggersRehandshakeAndRetry() throws IOException {
        AtomicInteger initializes = new AtomicInteger();
        HttpServer server = server(exchange -> {
            JsonObject request = readRequest(exchange);
            if (guardNotification(exchange, request)) {
                return;
            }
            String method = request.get("method").getAsString();
            String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if ("initialize".equals(method)) {
                int generation = initializes.incrementAndGet();
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", "2025-06-18");
                result.add("capabilities", new JsonObject());
                respond(exchange, 200, "application/json", jsonRpcResponse(request, result).toString(),
                        "Mcp-Session-Id", "sess-" + generation);
            } else if ("sess-1".equals(sessionId)) {
                respond(exchange, 404, "text/plain", "gone", null, null);
            } else {
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                respond(exchange, 200, "application/json", jsonRpcResponse(request, result).toString(),
                        "Mcp-Session-Id", sessionId);
            }
        });
        try (McpSession session = new McpSession(new StreamableHttpTransport(url(server), McpAuth.none()))) {
            session.open(TIMEOUT);
            JsonObject result = session.request("tools/list", new JsonObject(), TIMEOUT);
            assertTrue(result.get("ok").getAsBoolean());
            assertEquals(2, initializes.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unauthorizedChallengeTriggersAuthRetry() throws IOException {
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger challenges = new AtomicInteger();
        McpAuth retryingAuth = new McpAuth() {
            @Override
            public void apply(java.util.Map<String, String> headers) {
                headers.put("Authorization", "Bearer stale");
            }

            @Override
            public boolean onUnauthorized(int status, java.util.Map<String, String> responseHeaders) {
                challenges.incrementAndGet();
                return true;
            }
        };
        HttpServer server = server(exchange -> {
            JsonObject request = readRequest(exchange);
            if (posts.incrementAndGet() == 1) {
                respond(exchange, 401, "text/plain", "auth required", null, null);
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            respond(exchange, 200, "application/json", jsonRpcResponse(request, result).toString(), null, null);
        });
        try (StreamableHttpTransport transport =
                     new StreamableHttpTransport(url(server), retryingAuth)) {
            transport.start(message -> {
            }, () -> {
            });
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", 1);
            request.addProperty("method", "ping");
            transport.send(request);
            assertEquals(1, challenges.get());
            assertEquals(2, posts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteUrlsMustBeHttps() {
        assertThrows(IllegalArgumentException.class,
                () -> new StreamableHttpTransport("http://example.com/mcp", McpAuth.none()));
        assertNotNull(new StreamableHttpTransport("https://example.com/mcp", McpAuth.none()));
        assertNotNull(new StreamableHttpTransport("http://127.0.0.1:8080/mcp", McpAuth.none()));
        assertNotNull(new StreamableHttpTransport("http://localhost:8080/mcp", McpAuth.none()));
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private static JsonObject readRequest(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        return JsonParser.parseString(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Notifications get {@code 202} and no body. Returns true when handled. */
    private static boolean guardNotification(com.sun.net.httpserver.HttpExchange exchange, JsonObject request)
            throws IOException {
        if (request.has("id")) {
            return false;
        }
        respond(exchange, 202, null, "", null, null);
        return true;
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange,
            int status,
            String contentType,
            String body,
            String headerName,
            String headerValue) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        if (headerName != null) {
            exchange.getResponseHeaders().add(headerName, headerValue);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static JsonObject jsonRpcResponse(JsonObject request, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", request.get("id").getAsLong());
        response.add("result", result);
        return response;
    }
}
