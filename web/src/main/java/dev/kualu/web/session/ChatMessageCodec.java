package dev.kualu.web.session;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.TokenUsage;
import dev.kualu.llm.ToolCall;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ChatMessage} ↔ the {@code agent.session_messages} row columns.
 *
 * <p>Field mapping is written out rather than left to bean introspection:
 * {@link ChatMessage} is a record with an extra convenience constructor, and a
 * persistence boundary should not depend on which one a mapper happens to pick.
 * {@code role}/{@code content}/{@code toolCallId} are plain columns; the two
 * list fields ({@code toolCalls}, {@code images}) and the {@code usage} block
 * are JSON documents.
 */
public final class ChatMessageCodec {

    private final ObjectMapper mapper;

    public ChatMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** One message as row values; the list fields and usage are JSON text or {@code null}. */
    public record Encoded(String role, String content, String toolCallId, String toolCalls, String images,
                          String usage) {
    }

    public Encoded encode(ChatMessage message) {
        return new Encoded(
                message.role(),
                message.content(),
                message.toolCallId(),
                encodeToolCalls(message.toolCalls()),
                encodeImages(message.images()),
                encodeUsage(message.usage()));
    }

    public ChatMessage decode(Encoded row) {
        return new ChatMessage(
                row.role(),
                row.content(),
                row.toolCallId(),
                decodeToolCalls(row.toolCalls()),
                decodeImages(row.images()),
                decodeUsage(row.usage()));
    }

    /** @return JSON array of {@code {id, name, arguments}} objects; {@code null} when absent */
    String encodeToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        ArrayNode array = mapper.createArrayNode();
        for (ToolCall call : calls) {
            ObjectNode node = array.addObject();
            node.put("id", call.id());
            node.put("name", call.name());
            node.put("arguments", call.arguments());
        }
        return array.toString();
    }

    /** @return {@code null} when the JSON is absent or carries no calls */
    List<ToolCall> decodeToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root = parse(json, "toolCalls");
        if (!root.isArray() || root.isEmpty()) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            calls.add(new ToolCall(
                    node.get("id").asText(),
                    node.get("name").asText(),
                    node.get("arguments").asText()));
        }
        return List.copyOf(calls);
    }

    /** @return JSON array of image strings; {@code null} when absent */
    String encodeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        ArrayNode array = mapper.createArrayNode();
        for (String image : images) {
            array.add(image);
        }
        return array.toString();
    }

    /** @return {@code null} when the JSON is absent or carries no images */
    List<String> decodeImages(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root = parse(json, "images");
        if (!root.isArray() || root.isEmpty()) {
            return null;
        }
        List<String> images = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            images.add(node.asText());
        }
        return List.copyOf(images);
    }

    /** @return JSON object of the five token counters; {@code null} when absent */
    String encodeUsage(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("promptTokens", usage.promptTokens());
        node.put("completionTokens", usage.completionTokens());
        node.put("totalTokens", usage.totalTokens());
        node.put("reasoningTokens", usage.reasoningTokens());
        node.put("cachedTokens", usage.cachedTokens());
        return node.toString();
    }

    /** @return {@code null} when the JSON is absent */
    TokenUsage decodeUsage(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root = parse(json, "usage");
        if (!root.isObject()) {
            return null;
        }
        return new TokenUsage(
                intOf(root, "promptTokens"),
                intOf(root, "completionTokens"),
                intOf(root, "totalTokens"),
                intOf(root, "reasoningTokens"),
                intOf(root, "cachedTokens"));
    }

    private static int intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt();
    }

    private JsonNode parse(String json, String field) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("corrupt message history: bad " + field, ex);
        }
    }
}
