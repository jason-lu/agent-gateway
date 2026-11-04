package dev.kualu.llm;

import java.util.List;
import java.util.Objects;

/**
 * One chat turn for the OpenAI-style {@code messages} array.
 *
 * @param role      {@code "system"}, {@code "user"}, {@code "assistant"}, or {@code "tool"}
 * @param content   message text; may be empty on tool-call assistant turns and tool results
 * @param toolCallId tool result correlation id ({@code tool_call_id}); {@code null} except on tool turns
 * @param toolCalls assistant tool calls ({@code tool_calls}); {@code null} except on assistant turns that request tools
 */
public record ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (toolCalls != null) {
            if (toolCalls.isEmpty()) {
                throw new IllegalArgumentException("toolCalls must not be empty when present");
            }
            if (!"assistant".equals(role)) {
                throw new IllegalArgumentException("toolCalls are only valid on assistant turns");
            }
            if (toolCallId != null) {
                throw new IllegalArgumentException("assistant tool-call turns carry no toolCallId");
            }
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /** System/user/assistant turn without tool correlation. */
    public ChatMessage(String role, String content) {
        this(role, content, null, null);
    }

    /** Turn without tool calls (kept for message decoding). */
    public ChatMessage(String role, String content, String toolCallId) {
        this(role, content, toolCallId, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null);
    }

    /**
     * Assistant turn requesting tool executions. {@code content} may be empty
     * or carry text the model produced alongside the calls.
     */
    public static ChatMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, null, toolCalls);
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
        return new ChatMessage("tool", content, toolCallId, null);
    }
}
