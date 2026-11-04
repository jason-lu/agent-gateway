package dev.kualu.llm;

import java.util.Objects;

/**
 * One function call requested by the model ({@code tool_calls[]} entry).
 *
 * @param id        correlation id ({@code id}); the answering tool result must echo it
 * @param name      tool name ({@code function.name})
 * @param arguments arguments as a JSON object string ({@code function.arguments})
 */
public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
