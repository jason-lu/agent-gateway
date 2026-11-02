package dev.kualu.web.session;

import dev.kualu.llm.ChatMessage;
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

    /** @return a JSON array; {@code toolCallId} is omitted when absent */
    public String encode(List<ChatMessage> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
            if (message.toolCallId() != null) {
                node.put("toolCallId", message.toolCallId());
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
                    toolCallId));
        }
        return List.copyOf(messages);
    }
}
