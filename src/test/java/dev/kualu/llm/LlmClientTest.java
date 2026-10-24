package dev.kualu.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientTest {

    private HttpServer server;
    private LlmConfig config;
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedAccept = new AtomicReference<>();

    private int responseStatus = 200;
    private String responseBody = """
            {"choices":[{"message":{"role":"assistant","content":"Hello from model"}}]}
            """;
    private String streamBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handleChat);
        server.createContext("/chat/completions", this::handleChat);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        config = new LlmConfig(baseUrl, "test-api-key", "test-model");
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        capturedPath.set(exchange.getRequestURI().getPath());
        capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        capturedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
        capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        if (streamBody != null) {
            byte[] bytes = streamBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                exchange.close();
            }
            return;
        }

        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void chat_sendsBaseUrlApiKeyAndModel_andDecodesText() {
        LlmClient client = new LlmClient(config);

        String reply = client.chat("Hi there");

        assertEquals("Hello from model", reply);
        assertEquals("/v1/chat/completions", capturedPath.get());
        assertEquals("Bearer test-api-key", capturedAuth.get());

        JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
        assertEquals("test-model", body.get("model").getAsString());
        assertEquals("user", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("Hi there", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void chat_appendsChatCompletionsPath_whenMissing() {
        LlmClient client = new LlmClient(new LlmConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "k",
                "m"));

        client.chat("ping");

        assertEquals("/chat/completions", capturedPath.get());
    }

    @Test
    void chat_doesNotDuplicateChatCompletionsPath() {
        LlmClient client = new LlmClient(new LlmConfig(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "k",
                "m"));

        client.chat("ping");

        assertEquals("/v1/chat/completions", capturedPath.get());
    }

    @Test
    void chat_decodesEscapedText() {
        responseBody = """
                {"choices":[{"message":{"content":"line1\\nline2 \\"quoted\\""}}]}
                """;

        LlmClient client = new LlmClient(config);
        String reply = client.chat("x");

        assertEquals("line1\nline2 \"quoted\"", reply);
    }

    @Test
    void chat_httpError_throwsWithStatusAndMessage() {
        responseStatus = 401;
        responseBody = """
                {"error":{"message":"Invalid API key","type":"invalid_request_error"}}
                """;

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class, () -> client.chat("hi"));

        assertEquals(401, ex.statusCode());
        assertTrue(ex.getMessage().contains("Invalid API key"));
        assertTrue(ex.getMessage().contains("401"));
        assertTrue(ex.responseBody().contains("Invalid API key"));
    }

    @Test
    void chat_rateLimited_throwsWith429() {
        responseStatus = 429;
        responseBody = """
                {"error":{"message":"Slow down"}}
                """;

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class, () -> client.chat("hi"));

        assertEquals(429, ex.statusCode());
        assertTrue(ex.getMessage().contains("rate limited"));
    }

    @Test
    void chat_serverErrorWithoutJsonBody_throws() {
        responseStatus = 500;
        responseBody = "boom";

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class, () -> client.chat("hi"));

        assertEquals(500, ex.statusCode());
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void chat_emptyChoices_throws() {
        responseBody = """
                {"choices":[]}
                """;

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class, () -> client.chat("hi"));

        assertTrue(ex.getMessage().contains("no choices"));
    }

    @Test
    void chat_invalidJson_throws() {
        responseBody = "{\"choices\": [";

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class, () -> client.chat("hi"));

        assertTrue(ex.getMessage().contains("JSON"), ex.getMessage());
    }

    @Test
    void chat_blankMessage_throws() {
        LlmClient client = new LlmClient(config);
        assertThrows(LlmException.class, () -> client.chat("  "));
    }

    @Test
    void chatStream_yieldsDeltasAndSendsStreamFlag() {
        streamBody = """
                data: {"choices":[{"delta":{"content":"Hel"}}]}\s

                data: {"choices":[{"delta":{"content":"lo"}}]}\s

                data: {"choices":[{"delta":{}}]}\s

                data: [DONE]\s

                """;

        LlmClient client = new LlmClient(config);
        List<String> deltas = new ArrayList<>();
        try (Stream<String> stream = client.chatStream("Hi")) {
            stream.forEach(deltas::add);
        }

        assertEquals(List.of("Hel", "lo"), deltas);
        assertTrue(capturedBody.get().contains("\"stream\":true"));
        assertEquals("text/event-stream", capturedAccept.get());
    }

    @Test
    void chatStream_callbackReceivesDeltasAndReturnsFullText() {
        streamBody = """
                data: {"choices":[{"delta":{"content":"Hel"}}]}\s

                data: {"choices":[{"delta":{"content":"lo!"}}]}\s

                data: [DONE]\s

                """;

        LlmClient client = new LlmClient(config);
        List<String> seen = new ArrayList<>();
        String full = client.chatStream("Hi", seen::add);

        assertEquals("Hello!", full);
        assertEquals(List.of("Hel", "lo!"), seen);
    }

    @Test
    void chatStream_httpError_throwsWithStatus() {
        responseStatus = 401;
        streamBody = "{\"error\":{\"message\":\"Invalid API key\"}}";

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class, () -> {
            try (Stream<String> stream = client.chatStream("Hi")) {
                stream.forEach(d -> {});
            }
        });

        assertEquals(401, ex.statusCode());
        assertTrue(ex.getMessage().contains("Invalid API key"));
    }

    @Test
    void chatStream_errorEventInStream_throws() {
        streamBody = """
                data: {"choices":[{"delta":{"content":"partial"}}]}\s

                data: {"error":{"message":"model overloaded"}}\s

                data: [DONE]\s

                """;

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class, () -> {
            try (Stream<String> stream = client.chatStream("Hi")) {
                stream.forEach(d -> {});
            }
        });

        assertTrue(ex.getMessage().contains("model overloaded"));
    }

    @Test
    void chatStream_withoutDoneTerminator_stillYieldsDeltas() {
        streamBody = """
                data: {"choices":[{"delta":{"content":"only"}}]}\s

                """;

        LlmClient client = new LlmClient(config);
        List<String> deltas = new ArrayList<>();
        try (Stream<String> stream = client.chatStream("Hi")) {
            stream.forEach(deltas::add);
        }

        assertEquals(List.of("only"), deltas);
    }

    @Test
    void chatStream_blankMessage_throws() {
        LlmClient client = new LlmClient(config);
        assertThrows(LlmException.class, () -> client.chatStream(" "));
        assertThrows(LlmException.class, () -> client.chatStream(" ", d -> {}));
    }

    @Test
    void chat_doesNotSetStreamFlag() {
        LlmClient client = new LlmClient(config);
        client.chat("Hi");

        assertFalse(capturedBody.get().contains("\"stream\""));
    }

    @Test
    void chat_withMessages_sendsFullHistory() {
        LlmClient client = new LlmClient(config);

        String reply = client.chat(List.of(
                ChatMessage.system("be brief"),
                ChatMessage.user("Hi"),
                ChatMessage.assistant("Hello"),
                ChatMessage.user("Bye")));

        assertEquals("Hello from model", reply);

        JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
        var messages = body.getAsJsonArray("messages");
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("be brief", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("Hi", messages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
        assertEquals("Hello", messages.get(2).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals("Bye", messages.get(3).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void chat_emptyMessages_throws() {
        LlmClient client = new LlmClient(config);
        assertThrows(LlmException.class, () -> client.chat(List.of()));
    }

    @Test
    void chat_withToolMessage_sendsToolCallId() {
        LlmClient client = new LlmClient(config);

        client.chat(List.of(
                ChatMessage.user("run ls"),
                ChatMessage.tool("file1\nfile2", "call_abc")));

        JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
        var messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("tool", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("file1\nfile2", messages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals("call_abc", messages.get(1).getAsJsonObject().get("tool_call_id").getAsString());
        assertFalse(messages.get(0).getAsJsonObject().has("tool_call_id"));
    }

    @Test
    void chat_blankMessageContent_throws() {
        LlmClient client = new LlmClient(config);
        assertThrows(LlmException.class, () -> client.chat(List.of(ChatMessage.user(" "))));
    }

    @Test
    void chatStream_withMessages_sendsHistoryAndYieldsDeltas() {
        streamBody = """
                data: {"choices":[{"delta":{"content":"Hel"}}]}\s

                data: {"choices":[{"delta":{"content":"lo"}}]}\s

                data: [DONE]\s

                """;

        LlmClient client = new LlmClient(config);
        List<String> deltas = new ArrayList<>();
        try (Stream<String> stream = client.chatStream(List.of(
                ChatMessage.user("Hi"),
                ChatMessage.assistant("Hello"),
                ChatMessage.user("Go")))) {
            stream.forEach(deltas::add);
        }

        assertEquals(List.of("Hel", "lo"), deltas);

        JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
        assertEquals(3, body.getAsJsonArray("messages").size());
        assertTrue(capturedBody.get().contains("\"stream\":true"));
    }

    @Test
    void chatStream_callbackWithMessages_returnsFullText() {
        streamBody = """
                data: {"choices":[{"delta":{"content":"Hel"}}]}\s

                data: {"choices":[{"delta":{"content":"lo!"}}]}\s

                data: [DONE]\s

                """;

        LlmClient client = new LlmClient(config);
        List<String> seen = new ArrayList<>();
        String full = client.chatStream(List.of(ChatMessage.user("Hi")), seen::add);

        assertEquals("Hello!", full);
        assertEquals(List.of("Hel", "lo!"), seen);
    }
}
