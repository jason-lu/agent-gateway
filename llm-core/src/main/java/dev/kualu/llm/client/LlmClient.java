package dev.kualu.llm.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kualu.http.HttpClient;
import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.ToolSpec;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * LLM client built on {@link HttpClient}.
 *
 * <ul>
 *   <li>{@link #chat(String)} / {@link #chat(List)} — full reply text</li>
 *   <li>{@link #chatStream(String)} / {@link #chatStream(List)} — SSE text deltas as a {@link Stream}</li>
 *   <li>{@link #chatStream(String, Consumer)} / {@link #chatStream(List, Consumer)} — SSE deltas via callback</li>
 *   <li>{@link #complete(List, List)} / {@link #completeStream(List, List, Consumer)} — text plus
 *       any {@code tool_calls} the model requested (function calling)</li>
 * </ul>
 *
 * <p>Pass a full {@link List} of {@link ChatMessage} for multi-turn memory; the
 * single-{@link String} overloads send only one user message.
 */
public class LlmClient {

    private final HttpClient http;
    private final LlmConfig config;

    public LlmClient() {
        this(LlmConfig.fromEnvironment());
    }

    public LlmClient(LlmConfig config) {
        this(new HttpClient(), config);
    }

    public LlmClient(HttpClient http, LlmConfig config) {
        this.http = Objects.requireNonNull(http, "http");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Loads credentials from the environment / {@code .env} and uses them. */
    public static LlmClient fromEnvironment() {
        return new LlmClient(LlmConfig.fromEnvironment());
    }

    public LlmConfig config() {
        return config;
    }

    /** Sends a single user message and returns the full assistant reply text. */
    public String chat(String userMessage) {
        return chat(List.of(ChatMessage.user(userMessage)));
    }

    /** Sends the given conversation turns and returns the full assistant reply text. */
    public String chat(List<ChatMessage> messages) {
        HttpResponse<String> response =
                http.post(completionsUrl(), headers(false), requestJson(messages, false, List.of()));
        return ChatResponses.assistantText(response);
    }

    /**
     * Sends the given conversation turns with the tool definitions and returns the
     * completed turn: the reply text plus any tool calls the model requested.
     */
    public AssistantTurn complete(List<ChatMessage> messages, List<ToolSpec> tools) {
        HttpResponse<String> response =
                http.post(completionsUrl(), headers(false), requestJson(messages, false, tools));
        return ChatResponses.assistantTurn(response);
    }

    /**
     * Streams assistant text deltas as they arrive (SSE {@code chat/completions}).
     * The returned stream must be closed to release the HTTP connection.
     */
    public Stream<String> chatStream(String userMessage) {
        return chatStream(List.of(ChatMessage.user(userMessage)));
    }

    /**
     * Streams assistant text deltas for the given conversation turns (SSE {@code chat/completions}).
     * The returned stream must be closed to release the HTTP connection.
     */
    public Stream<String> chatStream(List<ChatMessage> messages) {
        return deltaStream(messages, List.of())
                .map(SseDelta::text)
                .filter(Objects::nonNull);
    }

    /**
     * Streams assistant text deltas to {@code onDelta} and returns the full reply text.
     */
    public String chatStream(String userMessage, Consumer<String> onDelta) {
        return chatStream(List.of(ChatMessage.user(userMessage)), onDelta);
    }

    /**
     * Streams assistant text deltas for the given conversation turns to {@code onDelta}
     * and returns the full reply text.
     */
    public String chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
        Objects.requireNonNull(onDelta, "onDelta");
        StringBuilder full = new StringBuilder();
        try (Stream<String> deltas = chatStream(messages)) {
            deltas.forEach(delta -> {
                full.append(delta);
                onDelta.accept(delta);
            });
        }
        return full.toString();
    }

    /**
     * Streams the turn with tool definitions: text deltas go to {@code onDelta}, and the
     * returned turn carries the full reply text plus any tool calls the model requested.
     */
    public AssistantTurn completeStream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onDelta) {
        Objects.requireNonNull(onDelta, "onDelta");
        StringBuilder full = new StringBuilder();
        ToolCallAccumulator calls = new ToolCallAccumulator();
        try (Stream<SseDelta> deltas = deltaStream(messages, tools)) {
            deltas.forEach(delta -> {
                if (delta.text() != null) {
                    full.append(delta.text());
                    onDelta.accept(delta.text());
                }
                delta.fragments().forEach(calls::add);
            });
        }
        return new AssistantTurn(full.toString(), calls.finish());
    }

    private Stream<SseDelta> deltaStream(List<ChatMessage> messages, List<ToolSpec> tools) {
        HttpResponse<Stream<String>> response =
                http.postLines(completionsUrl(), headers(true), requestJson(messages, true, tools));

        Stream<String> lines = response.body();
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String raw;
            try (lines) {
                raw = lines.reduce("", (a, b) -> a + b + "\n");
            }
            throw new LlmException(LlmErrorMessages.forHttp(status, raw), status, raw);
        }

        return guardIdle(lines)
                .map(String::trim)
                .filter(SseContentDeltas::isDataLine)
                .map(SseContentDeltas::dataOf)
                .takeWhile(data -> !"[DONE]".equals(data))
                .map(SseContentDeltas::extract)
                .filter(Objects::nonNull);
    }

    /**
     * Wraps the raw line stream with stall detection so a silent upstream aborts
     * with {@link IdleWatchdog#STALL_MESSAGE} instead of hanging forever.
     */
    private Stream<String> guardIdle(Stream<String> lines) {
        Duration idleTimeout = config.idleTimeout();
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            return lines;
        }
        return new IdleWatchdog(lines, idleTimeout).watch();
    }

    private Map<String, String> headers(boolean stream) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + config.apiKey());
        headers.put("Content-Type", "application/json");
        headers.put("Accept", stream ? "text/event-stream" : "application/json");
        return headers;
    }

    private String requestJson(List<ChatMessage> messages, boolean stream, List<ToolSpec> tools) {
        if (messages == null || messages.isEmpty()) {
            throw new LlmException("messages must not be empty");
        }

        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            Objects.requireNonNull(message, "message");
            boolean carriesTools = message.toolCalls() != null || "tool".equals(message.role());
            boolean carriesImages = message.images() != null && !message.images().isEmpty();
            if (message.content().isBlank() && !carriesTools && !carriesImages) {
                throw new LlmException("message content must not be blank");
            }
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            if (carriesImages) {
                item.add("content", multimodalContent(message));
            } else {
                item.addProperty("content", message.content());
            }
            if (message.toolCallId() != null) {
                item.addProperty("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null) {
                JsonArray calls = new JsonArray();
                for (ToolCall call : message.toolCalls()) {
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.addProperty("arguments", call.arguments());
                    JsonObject callItem = new JsonObject();
                    callItem.addProperty("id", call.id());
                    callItem.addProperty("type", "function");
                    callItem.add("function", function);
                    calls.add(callItem);
                }
                item.add("tool_calls", calls);
            }
            array.add(item);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.add("messages", array);
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolArray = new JsonArray();
            for (ToolSpec tool : tools) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name());
                function.addProperty("description", tool.description());
                function.add("parameters", JsonParser.parseString(tool.parametersJson()));
                JsonObject toolItem = new JsonObject();
                toolItem.addProperty("type", "function");
                toolItem.add("function", function);
                toolArray.add(toolItem);
            }
            body.add("tools", toolArray);
        }
        if (stream) {
            body.addProperty("stream", true);
        }
        return body.toString();
    }

    /**
     * {@code content} as a part array: optional text part plus one
     * {@code image_url} part per image (OpenAI-style vision input).
     */
    private static JsonArray multimodalContent(ChatMessage message) {
        JsonArray parts = new JsonArray();
        if (!message.content().isBlank()) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", message.content());
            parts.add(text);
        }
        for (String url : message.images()) {
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", url);
            JsonObject image = new JsonObject();
            image.addProperty("type", "image_url");
            image.add("image_url", imageUrl);
            parts.add(image);
        }
        return parts;
    }

    private String completionsUrl() {
        String base = config.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }
}
