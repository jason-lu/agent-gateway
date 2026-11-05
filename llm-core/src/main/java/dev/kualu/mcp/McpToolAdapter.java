package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kualu.llm.AgentTool;

import java.time.Duration;
import java.util.Objects;

/**
 * Bridges one remote MCP tool to the {@link AgentTool} contract the tool loop
 * speaks. Failures (bad arguments, transport loss, tool errors) are returned as
 * result text so the model can react; only unexpected faults throw.
 */
public final class McpToolAdapter implements AgentTool {

    /** OpenAI function-name limit ({@code ^[a-zA-Z0-9_-]{1,64}$}). */
    static final int MAX_NAME_LENGTH = 64;

    private final String serverName;
    private final String exposedName;
    private final McpRemoteTool tool;
    private final McpToolCaller caller;
    private final Duration timeout;
    private final int outputLimit;

    public McpToolAdapter(
            String serverName,
            McpRemoteTool tool,
            McpToolCaller caller,
            Duration timeout,
            int outputLimit) {
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (outputLimit < 1) {
            throw new IllegalArgumentException("outputLimit must be >= 1");
        }
        this.outputLimit = outputLimit;
        this.exposedName = exposeName(serverName, tool.name());
    }

    /**
     * Builds the name the model sees: {@code mcp_<server>_<tool>}, cleaned to the
     * OpenAI function-name charset and length. The original name is carried in the
     * description and sent to the server unchanged.
     */
    static String exposeName(String serverName, String toolName) {
        String cleaned = ("mcp_" + serverName + "_" + toolName).replaceAll("[^A-Za-z0-9_-]", "_");
        if (cleaned.length() <= MAX_NAME_LENGTH) {
            return cleaned;
        }
        String suffix = String.format("%04x", cleaned.hashCode() & 0xffff);
        return cleaned.substring(0, MAX_NAME_LENGTH - 5) + "_" + suffix;
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String description() {
        String description = tool.description();
        String origin = "(MCP tool: " + serverName + "/" + tool.name() + ")";
        return description.isBlank() ? origin : description + "\n" + origin;
    }

    @Override
    public String parametersJson() {
        return tool.inputSchema().toString();
    }

    @Override
    public String execute(String argumentsJson) {
        JsonObject arguments;
        try {
            arguments = parseArguments(argumentsJson);
        } catch (RuntimeException ex) {
            return "error: invalid arguments json: " + ex.getMessage();
        }
        try {
            return render(caller.call(tool.name(), arguments, timeout));
        } catch (Exception ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return "error: mcp tool '" + serverName + "/" + tool.name() + "' failed: " + reason;
        }
    }

    private static JsonObject parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(argumentsJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("expected a json object");
        }
        return parsed.getAsJsonObject();
    }

    private String render(McpCallResult result) {
        StringBuilder text = new StringBuilder();
        JsonArray content = result.content();
        for (int i = 0; i < content.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(renderBlock(content.get(i)));
        }
        if (result.structuredContent() != null) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("(structuredContent): ").append(result.structuredContent());
        }
        if (text.isEmpty() && result.error()) {
            text.append("error: tool returned no content");
        }
        String rendered = text.toString();
        if (result.error() && !rendered.startsWith("error:")) {
            rendered = "error: " + rendered;
        }
        return truncate(rendered, outputLimit);
    }

    private static String renderBlock(JsonElement element) {
        if (!element.isJsonObject()) {
            return element.toString();
        }
        JsonObject block = element.getAsJsonObject();
        String type = block.has("type") && block.get("type").isJsonPrimitive()
                ? block.get("type").getAsString()
                : "text";
        if ("text".equals(type)) {
            return block.has("text") && block.get("text").isJsonPrimitive()
                    ? block.get("text").getAsString()
                    : "";
        }
        String mime = block.has("mimeType") && block.get("mimeType").isJsonPrimitive()
                ? block.get("mimeType").getAsString()
                : "";
        if ("image".equals(type) || "audio".equals(type)) {
            return "[" + type + " omitted: " + mime + "]";
        }
        return "[" + type + " block omitted]";
    }

    private static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "… [truncated, " + text.length() + " chars total]";
    }
}
