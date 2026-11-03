package dev.kualu.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kualu.http.HttpClient;

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
                http.post(completionsUrl(), headers(false), requestJson(messages, false));
        return ChatResponses.assistantText(response);
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
        HttpResponse<Stream<String>> response =
                http.postLines(completionsUrl(), headers(true), requestJson(messages, true));

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

    private Map<String, String> headers(boolean stream) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + config.apiKey());
        headers.put("Content-Type", "application/json");
        headers.put("Accept", stream ? "text/event-stream" : "application/json");
        return headers;
    }

    private String requestJson(List<ChatMessage> messages, boolean stream) {
        if (messages == null || messages.isEmpty()) {
            throw new LlmException("messages must not be empty");
        }

        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            Objects.requireNonNull(message, "message");
            if (message.content().isBlank()) {
                throw new LlmException("message content must not be blank");
            }
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            if (message.toolCallId() != null) {
                item.addProperty("tool_call_id", message.toolCallId());
            }
            array.add(item);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.add("messages", array);
        if (stream) {
            body.addProperty("stream", true);
        }
        return body.toString();
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
