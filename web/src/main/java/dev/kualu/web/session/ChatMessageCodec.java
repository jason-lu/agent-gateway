package dev.kualu.web.session;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ToolCall;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ChatMessage} ↔ JSON for the {@code messages jsonb} column.
 *
 * <p>Field mapping is written out rather than left to bean introspection:
 * {@link ChatMessage} is a record with an extra convenience constructor, and a
 * persistence boundary should not depend on which one a mapper happens to pick.
 */
public final class ChatMessageCodec {

    private final ObjectMapper mapper;

    public ChatMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @return a JSON array; {@code toolCallId}, {@code toolCalls} and {@code images} are omitted when absent */
    public String encode(List<ChatMessage> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
            if (message.toolCallId() != null) {
                node.put("toolCallId", message.toolCallId());
            }
            if (message.toolCalls() != null) {
                ArrayNode calls = node.putArray("toolCalls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("name", call.name());
                    callNode.put("arguments", call.arguments());
                }
            }
            if (message.images() != null) {
                ArrayNode images = node.putArray("images");
                for (String image : message.images()) {
                    images.add(image);
                }
            }
        }
        return array.toString();
    }

    public List<ChatMessage> decode(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("corrupt message history", ex);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("corrupt message history: expected a JSON array");
        }
        List<ChatMessage> messages = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            String toolCallId = node.hasNonNull("toolCallId") ? node.get("toolCallId").asText() : null;
            messages.add(new ChatMessage(
                    node.get("role").asText(),
                    node.get("content").asText(),
                    toolCallId,
                    decodeToolCalls(node.get("toolCalls")),
                    decodeImages(node.get("images"))));
        }
        return List.copyOf(messages);
    }

    /** @return {@code null} when the node is absent or carries no calls */
    private static List<ToolCall> decodeToolCalls(JsonNode callsNode) {
        if (callsNode == null || !callsNode.isArray() || callsNode.size() == 0) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>(callsNode.size());
        for (JsonNode callNode : callsNode) {
            calls.add(new ToolCall(
                    callNode.get("id").asText(),
                    callNode.get("name").asText(),
                    callNode.get("arguments").asText()));
        }
        return List.copyOf(calls);
    }

    /** @return {@code null} when the node is absent or carries no images */
    private static List<String> decodeImages(JsonNode imagesNode) {
        if (imagesNode == null || !imagesNode.isArray() || imagesNode.size() == 0) {
            return null;
        }
        List<String> images = new ArrayList<>(imagesNode.size());
        for (JsonNode imageNode : imagesNode) {
            images.add(imageNode.asText());
        }
        return List.copyOf(images);
    }
}
