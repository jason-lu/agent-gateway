package dev.kualu.llm.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    private volatile boolean stallAfterStream;
    private volatile boolean releaseStalledHandler;

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
            if (stallAfterStream) {
                // Chunked response: write a first event, then stay silent until released.
                exchange.sendResponseHeaders(200, 0);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.flush();
                while (!releaseStalledHandler) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                exchange.close();
                return;
            }
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
        releaseStalledHandler = true;
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
    void chat_withImages_serializesMultimodalContent() {
        LlmClient client = new LlmClient(config);
        List<String> images = List.of("data:image/png;base64,AAAA", "https://example.com/cat.png");

        client.chat(List.of(ChatMessage.user("what is this?", images)));

        JsonObject message = JsonParser.parseString(capturedBody.get()).getAsJsonObject()
                .getAsJsonArray("messages").get(0).getAsJsonObject();
        assertTrue(message.get("content").isJsonArray(), "image turns must serialize as a part array");
        JsonArray parts = message.getAsJsonArray("content");
        assertEquals(3, parts.size());
        assertEquals("text", parts.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("what is this?", parts.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("image_url", parts.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("data:image/png;base64,AAAA",
                parts.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString());
        assertEquals("https://example.com/cat.png",
                parts.get(2).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString());
    }

    @Test
    void chat_imageOnlyMessage_omitsTheBlankTextPart() {
        LlmClient client = new LlmClient(config);

        client.chat(List.of(ChatMessage.user("", List.of("data:image/jpeg;base64,BBBB"))));

        JsonObject message = JsonParser.parseString(capturedBody.get()).getAsJsonObject()
                .getAsJsonArray("messages").get(0).getAsJsonObject();
        JsonArray parts = message.getAsJsonArray("content");
        assertEquals(1, parts.size());
        assertEquals("image_url", parts.get(0).getAsJsonObject().get("type").getAsString());
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
    void chatStream_stalledUpstream_throwsFriendlyStallError() {
        stallAfterStream = true;
        streamBody = "data: {\"choices\":[{\"delta\":{\"content\":\"par\"}}]}\n\n";

        LlmConfig stallConfig = new LlmConfig(
                config.baseUrl(), config.apiKey(), config.model(), Duration.ofMillis(600));
        LlmClient client = new LlmClient(stallConfig);
        List<String> seen = new ArrayList<>();

        long startedAt = System.nanoTime();
        LlmException ex = assertThrows(LlmException.class, () -> client.chatStream("Hi", seen::add));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(IdleWatchdog.STALL_MESSAGE, ex.getMessage());
        assertEquals(List.of("par"), seen);
        assertTrue(elapsedMs < 10_000, "stall should abort promptly, took " + elapsedMs + "ms");
    }

    @Test
    void chatStream_idleWatchdogDisabled_streamCompletesNormally() {
        streamBody = """
                data: {"choices":[{"delta":{"content":"quick"}}]}\s

                data: [DONE]\s

                """;

        LlmConfig noWatchdog = new LlmConfig(
                config.baseUrl(), config.apiKey(), config.model(), Duration.ZERO);
        LlmClient client = new LlmClient(noWatchdog);
        List<String> seen = new ArrayList<>();
        String full = client.chatStream("Hi", seen::add);

        assertEquals("quick", full);
        assertEquals(List.of("quick"), seen);
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

    private static List<ToolSpec> execTools() {
        return List.of(new ToolSpec("exec", "run a command", "{\"type\":\"object\"}"));
    }

    @Test
    void complete_sendsToolsAndParsesToolCalls() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"exec","arguments":"{\\"command\\":\\"ls\\"}"}}
                ]}}]}
                """;

        LlmClient client = new LlmClient(config);
        AssistantTurn turn = client.complete(List.of(ChatMessage.user("run ls")), execTools());

        assertTrue(turn.hasToolCalls());
        assertEquals("", turn.content());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call_1", turn.toolCalls().get(0).id());
        assertEquals("exec", turn.toolCalls().get(0).name());
        assertEquals("{\"command\":\"ls\"}", turn.toolCalls().get(0).arguments());

        JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        assertEquals("exec", tool.getAsJsonObject("function").get("name").getAsString());
        assertEquals("run a command", tool.getAsJsonObject("function").get("description").getAsString());
        assertEquals("object",
                tool.getAsJsonObject("function").getAsJsonObject("parameters").get("type").getAsString());
    }

    @Test
    void complete_withoutTools_sendsNoToolsArray() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"hi"}}]}
                """;

        LlmClient client = new LlmClient(config);
        AssistantTurn turn = client.complete(List.of(ChatMessage.user("hi")), List.of());

        assertEquals("hi", turn.content());
        assertFalse(turn.hasToolCalls());
        assertFalse(capturedBody.get().contains("\"tools\""));
    }

    @Test
    void complete_serializesAssistantToolCallTurnsAndEmptyToolResults() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"done"}}]}
                """;

        LlmClient client = new LlmClient(config);
        client.complete(List.of(
                ChatMessage.user("run ls"),
                ChatMessage.assistantToolCalls("", List.of(new ToolCall("call_1", "exec", "{\"command\":\"ls\"}"))),
                ChatMessage.tool("", "call_1")), execTools());

        JsonObject body = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
        var messages = body.getAsJsonArray("messages");

        JsonObject assistant = messages.get(1).getAsJsonObject();
        assertEquals("assistant", assistant.get("role").getAsString());
        JsonObject call = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", call.get("id").getAsString());
        assertEquals("function", call.get("type").getAsString());
        assertEquals("exec", call.getAsJsonObject("function").get("name").getAsString());
        assertEquals("{\"command\":\"ls\"}", call.getAsJsonObject("function").get("arguments").getAsString());

        JsonObject toolResult = messages.get(2).getAsJsonObject();
        assertEquals("tool", toolResult.get("role").getAsString());
        assertEquals("", toolResult.get("content").getAsString());
        assertEquals("call_1", toolResult.get("tool_call_id").getAsString());
    }

    @Test
    void complete_contentlessResponseWithoutToolCalls_throws() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":null}}]}
                """;

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class,
                () -> client.complete(List.of(ChatMessage.user("hi")), execTools()));

        assertTrue(ex.getMessage().contains("had no content"));
    }

    @Test
    void completeStream_accumulatesToolCallFragments() {
        streamBody = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"exec","arguments":""}}]}}]}\s

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"comm"}}]}}]}\s

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"and\\":\\"ls\\"}"}}]}}]}\s

                data: [DONE]\s

                """;

        LlmClient client = new LlmClient(config);
        List<String> seen = new ArrayList<>();
        AssistantTurn turn = client.completeStream(List.of(ChatMessage.user("run ls")), execTools(), seen::add);

        assertEquals(List.of(), seen);
        assertTrue(turn.hasToolCalls());
        assertEquals("call_9", turn.toolCalls().get(0).id());
        assertEquals("exec", turn.toolCalls().get(0).name());
        assertEquals("{\"command\":\"ls\"}", turn.toolCalls().get(0).arguments());
    }

    @Test
    void completeStream_mixedTextAndToolCallsInOneTurn() {
        streamBody = """
                data: {"choices":[{"delta":{"content":"let me look"}}]}\s

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"exec","arguments":"{}"}}]}}]}\s

                data: [DONE]\s

                """;

        LlmClient client = new LlmClient(config);
        List<String> seen = new ArrayList<>();
        AssistantTurn turn = client.completeStream(List.of(ChatMessage.user("hi")), execTools(), seen::add);

        assertEquals(List.of("let me look"), seen);
        assertEquals("let me look", turn.content());
        assertEquals(1, turn.toolCalls().size());
    }

    @Test
    void completeStream_toolCallMissingItsId_throws() {
        streamBody = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"exec","arguments":"{}"}}]}}]}\s

                data: [DONE]\s

                """;

        LlmClient client = new LlmClient(config);
        LlmException ex = assertThrows(LlmException.class,
                () -> client.completeStream(List.of(ChatMessage.user("hi")), execTools(), d -> {}));

        assertTrue(ex.getMessage().contains("id or name"), ex.getMessage());
    }
}
