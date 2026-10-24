package dev.kualu.llm;

import java.util.Objects;

/**
 * One chat turn for the OpenAI-style {@code messages} array.
 *
 * @param role      {@code "system"}, {@code "user"}, {@code "assistant"}, or {@code "tool"}
 * @param content   message text
 * @param toolCallId tool result correlation id ({@code tool_call_id}); {@code null} except on tool turns
 */
public record ChatMessage(String role, String content, String toolCallId) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }

    /** System/user/assistant turn without a tool call id. */
    public ChatMessage(String role, String content) {
        this(role, content, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null);
    }

    /**
     * Tool result turn. {@code toolCallId} must match the assistant
     * {@code tool_calls[].id} this result answers.
     */
    public static ChatMessage tool(String content, String toolCallId) {
        Objects.requireNonNull(toolCallId, "toolCallId");
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        return new ChatMessage("tool", content, toolCallId);
    }
}
