package dev.kualu.llm;

/**
 * A tool the model can invoke via function calling.
 *
 * <p>Implementations must be safe to call repeatedly and should return failures
 * (bad arguments, command errors) as result text so the model can react; only
 * unexpected faults should throw.
 */
public interface AgentTool {

    /** Function name sent to the model. */
    String name();

    /** What the tool does; shown to the model. */
    String description();

    /** JSON Schema (as text) describing the arguments object. */
    String parametersJson();

    /**
     * Runs the tool with the model-supplied arguments (a JSON object string) and
     * returns the text placed in the {@code tool} result message.
     */
    String execute(String argumentsJson);
}
