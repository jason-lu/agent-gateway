package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kualu.llm.AgentTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
     *
     * <p>Non-ASCII characters carry no information after cleaning (every CJK tool
     * name would collapse to the same run of {@code _}), so a name containing any
     * gets a stable digest of the original {@code server/tool} appended instead —
     * distinct tools stay distinct, and the name is the same across restarts.
     * Pure-ASCII names keep their existing readable form.
     */
    static String exposeName(String serverName, String toolName) {
        String raw = "mcp_" + serverName + "_" + toolName;
        String cleaned = raw.replaceAll("[^A-Za-z0-9_-]", "_");
        if (!isAscii(raw)) {
            String suffix = "_" + digest(serverName + "/" + toolName);
            String readable = cleaned.replaceAll("_{2,}", "_").replaceAll("_+$", "");
            int keep = Math.min(readable.length(), MAX_NAME_LENGTH - suffix.length());
            return readable.substring(0, keep) + suffix;
        }
        if (cleaned.length() <= MAX_NAME_LENGTH) {
            return cleaned;
        }
        String suffix = String.format("%04x", cleaned.hashCode() & 0xffff);
        return cleaned.substring(0, MAX_NAME_LENGTH - 5) + "_" + suffix;
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** First 8 hex chars of SHA-256 over the UTF-8 bytes. */
    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
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
