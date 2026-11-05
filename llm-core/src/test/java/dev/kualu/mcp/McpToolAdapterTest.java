package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolAdapterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void exposedNameIsCleanedAndPrefixed() {
        assertEquals("mcp_playwright_navigate",
                McpToolAdapter.exposeName("playwright", "navigate"));
        assertEquals("mcp_my_server_tool_x",
                McpToolAdapter.exposeName("my.server", "tool/x"));
    }

    @Test
    void longNamesAreTruncatedTo64WithHashSuffix() {
        String name = McpToolAdapter.exposeName(
                "averyveryverylongservername", "averyveryverylongtoolnamethatgoesonandonandon");
        assertEquals(64, name.length());
        assertTrue(name.startsWith("mcp_"));
        assertTrue(name.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void textBlocksAreJoinedAndStructuredContentAppended() {
        JsonArray content = new JsonArray();
        content.add(textBlock("first"));
        content.add(textBlock("second"));
        JsonObject structured = new JsonObject();
        structured.addProperty("n", 1);
        String result = returning(content, false, structured).execute("{\"x\":1}");
        assertTrue(result.contains("first"));
        assertTrue(result.contains("second"));
        assertTrue(result.contains("(structuredContent)"));
    }

    @Test
    void errorFlagPrefixesResultText() {
        assertEquals("error: boom", returning(textBlock("boom"), true, null).execute("{}"));
    }

    @Test
    void nonTextBlocksBecomePlaceholders() {
        JsonArray content = new JsonArray();
        JsonObject image = new JsonObject();
        image.addProperty("type", "image");
        image.addProperty("mimeType", "image/png");
        content.add(image);
        content.add(textBlock("caption"));
        String result = returning(content, false, null).execute("{}");
        assertTrue(result.contains("[image omitted: image/png]"));
        assertTrue(result.contains("caption"));
    }

    @Test
    void invalidArgumentsJsonReturnsErrorText() {
        assertTrue(returning(textBlock("x"), false, null)
                .execute("not json").startsWith("error: invalid arguments json"));
    }

    @Test
    void callerFailureReturnsErrorText() {
        McpToolAdapter adapter = withCaller((name, args, timeout) -> {
            throw new McpException("mcp transport closed");
        });
        assertTrue(adapter.execute("{}").startsWith("error: mcp tool 'srv/tool' failed"));
    }

    @Test
    void outputIsTruncatedToLimit() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longText.append("0123456789");
        }
        McpToolAdapter adapter = new McpToolAdapter("srv",
                new McpRemoteTool("tool", "", new JsonObject()),
                (name, args, timeout) -> new McpCallResult(content(textBlock(longText.toString())), false, null),
                TIMEOUT, 100);
        String result = adapter.execute("{}");
        assertTrue(result.length() < 200);
        assertTrue(result.contains("truncated"));
    }

    @Test
    void descriptionMentionsOrigin() {
        McpToolAdapter adapter = returning(textBlock("x"), false, null);
        assertTrue(adapter.description().contains("(MCP tool: srv/tool)"));
    }

    @Test
    void blankOutputLimitRejected() {
        assertThrows(IllegalArgumentException.class, () -> new McpToolAdapter("srv",
                new McpRemoteTool("tool", "", new JsonObject()),
                (name, args, timeout) -> new McpCallResult(new JsonArray(), false, null),
                TIMEOUT, 0));
    }

    private static JsonArray content(JsonObject... blocks) {
        JsonArray array = new JsonArray();
        for (JsonObject block : blocks) {
            array.add(block);
        }
        return array;
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static McpToolAdapter returning(JsonArray content, boolean error, JsonObject structured) {
        McpCallResult result = new McpCallResult(content, error, structured);
        return withCaller((name, args, timeout) -> result);
    }

    private static McpToolAdapter returning(JsonObject block, boolean error, JsonObject structured) {
        return returning(content(block), error, structured);
    }

    private static McpToolAdapter withCaller(McpToolCaller caller) {
        return new McpToolAdapter("srv", new McpRemoteTool("tool", "", new JsonObject()),
                caller, TIMEOUT, 1000);
    }
}
