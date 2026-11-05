package dev.kualu.llm;

import java.util.List;
import java.util.Objects;

/**
 * One chat turn for the OpenAI-style {@code messages} array.
 *
 * @param role      {@code "system"}, {@code "user"}, {@code "assistant"}, or {@code "tool"}
 * @param content   message text; may be empty on tool-call assistant turns, tool results,
 *                  and image-only user turns
 * @param toolCallId tool result correlation id ({@code tool_call_id}); {@code null} except on tool turns
 * @param toolCalls assistant tool calls ({@code tool_calls}); {@code null} except on assistant turns that request tools
 * @param images    image URLs or {@code data:} URIs for vision models ({@code image_url} parts);
 *                  {@code null} except on user turns. Text and images are sent as one
 *                  multimodal {@code content} array.
 */
public record ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls,
                          List<String> images) {

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
        if (images != null) {
            if (images.isEmpty()) {
                throw new IllegalArgumentException("images must not be empty when present");
            }
            if (!"user".equals(role)) {
                throw new IllegalArgumentException("images are only valid on user turns");
            }
            for (String image : images) {
                if (image == null || image.isBlank()) {
                    throw new IllegalArgumentException("images must not contain blank entries");
                }
            }
            images = List.copyOf(images);
        }
    }

    /** System/user/assistant turn without tool correlation. */
    public ChatMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    /** Turn without tool calls (kept for message decoding). */
    public ChatMessage(String role, String content, String toolCallId) {
        this(role, content, toolCallId, null, null);
    }

    /** Turn without images (kept for message decoding). */
    public ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this(role, content, toolCallId, toolCalls, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null, null);
    }

    /**
     * User turn with text and/or images. {@code images} may be {@code null} or
     * empty; entries are image URLs or {@code data:} URIs.
     */
    public static ChatMessage user(String content, List<String> images) {
        if (images == null || images.isEmpty()) {
            return user(content);
        }
        return new ChatMessage("user", content, null, null, images);
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
