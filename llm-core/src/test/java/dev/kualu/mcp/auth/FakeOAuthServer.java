package dev.kualu.mcp.auth;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Scripted OAuth authorization server + protected resource for tests. */
final class FakeOAuthServer implements AutoCloseable {

    final HttpServer server;
    final AtomicInteger tokenCalls = new AtomicInteger();
    final AtomicInteger devicePolls = new AtomicInteger();
    /** Number of device-code polls answered with {@code authorization_pending}. */
    volatile int devicePendingFirst = 0;

    FakeOAuthServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String resourceUrl() {
        return origin() + "/mcp";
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        switch (path) {
            case "/.well-known/oauth-protected-resource" -> json(exchange, 200, """
                    {"authorization_servers": ["%s"]}""".formatted(origin()));
            case "/.well-known/oauth-authorization-server" -> json(exchange, 200, """
                    {"authorization_endpoint": "%s/authorize",
                     "token_endpoint": "%s/token",
                     "registration_endpoint": "%s/register",
                     "device_authorization_endpoint": "%s/device",
                     "code_challenge_methods_supported": ["S256"]}""".formatted(origin(), origin(), origin(), origin()));
            case "/register" -> json(exchange, 201, "{\"client_id\": \"dyn-client\"}");
            case "/token" -> token(exchange);
            case "/device" -> json(exchange, 200, """
                    {"device_code": "dev-1", "user_code": "ABCD-1234",
                     "verification_uri": "%s/verify", "interval": 1}""".formatted(origin()));
            default -> json(exchange, 404, "{\"error\": \"not_found\"}");
        }
    }

    private void token(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        tokenCalls.incrementAndGet();
        Map<String, String> params = form(exchange);
        String grant = params.getOrDefault("grant_type", "");
        switch (grant) {
            case "authorization_code" -> json(exchange, 200, """
                    {"access_token": "access-from-code", "refresh_token": "refresh-1",
                     "token_type": "Bearer", "expires_in": 3600}""");
            case "refresh_token" -> json(exchange, 200, """
                    {"access_token": "access-refreshed", "token_type": "Bearer", "expires_in": 3600}""");
            case "urn:ietf:params:oauth:grant-type:device_code" -> {
                if (devicePolls.incrementAndGet() <= devicePendingFirst) {
                    json(exchange, 400, "{\"error\": \"authorization_pending\"}");
                } else {
                    json(exchange, 200, """
                            {"access_token": "access-device", "token_type": "Bearer", "expires_in": 3600}""");
                }
            }
            default -> json(exchange, 400, "{\"error\": \"unsupported_grant_type\"}");
        }
    }

    private static Map<String, String> form(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static void json(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
