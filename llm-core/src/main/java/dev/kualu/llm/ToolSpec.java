package dev.kualu.llm;

import java.util.Objects;

/**
 * Tool definition for the request {@code tools} array (OpenAI-style function calling).
 *
 * @param name           function name
 * @param description    what the tool does; shown to the model
 * @param parametersJson JSON Schema of the arguments object, as text
 */
public record ToolSpec(String name, String description, String parametersJson) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJson, "parametersJson");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** Derives the wire definition from a runnable {@link AgentTool}. */
    public static ToolSpec of(AgentTool tool) {
        Objects.requireNonNull(tool, "tool");
        return new ToolSpec(tool.name(), tool.description(), tool.parametersJson());
    }
}
