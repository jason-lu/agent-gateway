package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Result of {@code tools/call}: the raw content blocks plus the error flag.
 *
 * @param content           {@code content} array (never null, may be empty)
 * @param error             true when the tool reported failure ({@code isError})
 * @param structuredContent {@code structuredContent} object, or null
 */
public record McpCallResult(JsonArray content, boolean error, JsonObject structuredContent) {

    public McpCallResult {
        content = content == null ? new JsonArray() : content;
    }
}
