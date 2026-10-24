package dev.kualu.llm;

import java.util.Objects;

/**
 * One chat turn for the OpenAI-style {@code messages} array.
 *
 * @param role    {@code "system"}, {@code "user"}, or {@code "assistant"}
 * @param content message text
 */
public record ChatMessage(String role, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
