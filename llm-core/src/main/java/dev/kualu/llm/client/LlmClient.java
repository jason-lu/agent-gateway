package dev.kualu.llm.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.kualu.http.HttpClient;
import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.ToolSpec;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
    private final ImageSource imageSource;

    public LlmClient() {
        this(LlmConfig.fromEnvironment());
    }

    public LlmClient(LlmConfig config) {
        this(new HttpClient(), config);
    }

    public LlmClient(HttpClient http, LlmConfig config) {
        this(http, config, ImageSource.passThrough());
    }

    public LlmClient(HttpClient http, LlmConfig config, ImageSource imageSource) {
        this.http = Objects.requireNonNull(http, "http");
        this.config = Objects.requireNonNull(config, "config");
        this.imageSource = Objects.requireNonNull(imageSource, "imageSource");
    }

    /** Loads credentials from the environment / {@code .env} and uses them. */
    public static LlmClient fromEnvironment() {
        return new LlmClient(LlmConfig.fromEnvironment());
    }

    /**
     * Model IDs offered by an OpenAI-compatible upstream ({@code GET /models}).
     * Only the connection is needed — the model name is exactly what this listing
     * helps choose. Order follows the upstream response.
     */
    public static List<String> listModels(HttpClient http, String baseUrl, String apiKey) {
        Objects.requireNonNull(http, "http");
        requireText(baseUrl, "baseUrl");
        requireText(apiKey, "apiKey");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey.trim());
        headers.put("Accept", "application/json");

        HttpResponse<String> response = http.get(apiRoot(baseUrl) + "/models", headers);
        int status = response.statusCode();
        String raw = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            throw new LlmException(LlmErrorMessages.forHttp(status, raw), status, raw);
        }
        return parseModelIds(raw, status);
    }

    /** The {@code data[].id} strings of a {@code GET /models} body. */
    private static List<String> parseModelIds(String raw, int status) {
        if (raw.isBlank()) {
            throw new LlmException("LLM model list response was empty", status, raw);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (JsonSyntaxException e) {
            throw new LlmException("LLM model list response was not valid JSON", status, raw);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new LlmException("LLM model list response was not a JSON object", status, raw);
        }
        JsonObject root = parsed.getAsJsonObject();

        JsonElement error = root.get("error");
        if (error != null && !error.isJsonNull()) {
            throw new LlmException(LlmErrorMessages.fromApiError(error), status, raw);
        }

        JsonElement data = root.get("data");
        if (data == null || !data.isJsonArray()) {
            throw new LlmException("LLM model list response had no data array", status, raw);
        }
        List<String> ids = new ArrayList<>();
        for (JsonElement item : data.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonElement id = item.getAsJsonObject().get("id");
            if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                String value = id.getAsString();
                if (!value.isBlank()) {
                    ids.add(value);
                }
            }
        }
        return List.copyOf(ids);
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
        return chat(messages, null);
    }

    /**
     * Sends the given conversation turns with an output-token cap and returns the
     * full assistant reply text. {@code maxTokens} of {@code null} leaves the cap
     * to the provider default.
     */
    public String chat(List<ChatMessage> messages, Integer maxTokens) {
        HttpResponse<String> response =
                http.post(completionsUrl(), headers(false), requestJson(messages, false, List.of(), maxTokens));
        return ChatResponses.assistantText(response);
    }

    /**
     * Sends the given conversation turns with the tool definitions and returns the
     * completed turn: the reply text plus any tool calls the model requested.
     */
    public AssistantTurn complete(List<ChatMessage> messages, List<ToolSpec> tools) {
        HttpResponse<String> response =
                http.post(completionsUrl(), headers(false), requestJson(messages, false, tools, null));
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
                http.postLines(completionsUrl(), headers(true), requestJson(messages, true, tools, null));

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

    private String requestJson(List<ChatMessage> messages, boolean stream, List<ToolSpec> tools, Integer maxTokens) {
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
        if (maxTokens != null) {
            body.addProperty("max_tokens", maxTokens);
        }
        return body.toString();
    }

    /**
     * {@code content} as a part array: optional text part plus one
     * {@code image_url} part per image (OpenAI-style vision input). Images are
     * run through the {@link ImageSource} first so store references can be
     * inlined for the model.
     */
    private JsonArray multimodalContent(ChatMessage message) {
        JsonArray parts = new JsonArray();
        if (!message.content().isBlank()) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", message.content());
            parts.add(text);
        }
        for (String url : message.images()) {
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", imageSource.materialize(url));
            JsonObject image = new JsonObject();
            image.addProperty("type", "image_url");
            image.add("image_url", imageUrl);
            parts.add(image);
        }
        return parts;
    }

    private String completionsUrl() {
        return apiRoot(config.baseUrl()) + "/chat/completions";
    }

    /**
     * The endpoint prefix shared by {@code /chat/completions} and {@code /models}:
     * trailing slashes and a trailing {@code /chat/completions} are stripped so a
     * config may name either the API root or the full completions URL.
     */
    private static String apiRoot(String baseUrl) {
        String base = requireText(baseUrl, "baseUrl");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        return base;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new LlmException("LLM config " + name + " must not be blank");
        }
        return trimmed;
    }
}
