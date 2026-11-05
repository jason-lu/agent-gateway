package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Scripted MCP server speaking NDJSON over stdio (one JSON-RPC message per line).
 * Used by {@code StdioTransportTest} as a real child process.
 */
public final class FakeMcpServer {

    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        java.io.BufferedWriter out = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject message = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            JsonObject response = respond(message);
            if (response != null) {
                out.write(response.toString());
                out.write('\n');
                out.flush();
            }
        }
    }

    private static JsonObject respond(JsonObject message) {
        if (!message.has("method")) {
            return null;
        }
        String method = message.get("method").getAsString();
        long id = message.has("id") ? message.get("id").getAsLong() : -1;
        if (!message.has("id")) {
            return null;
        }
        JsonObject result = new JsonObject();
        switch (method) {
            case "initialize" -> {
                result.addProperty("protocolVersion", "2025-06-18");
                result.add("capabilities", new JsonObject());
                JsonObject info = new JsonObject();
                info.addProperty("name", "fake-mcp-server");
                info.addProperty("version", "test");
                result.add("serverInfo", info);
            }
            case "tools/list" -> {
                JsonArray tools = new JsonArray();
                JsonObject tool = new JsonObject();
                tool.addProperty("name", "echo");
                tool.addProperty("description", "Echoes the input text");
                JsonObject schema = new JsonObject();
                schema.addProperty("type", "object");
                tool.add("inputSchema", schema);
                tools.add(tool);
                result.add("tools", tools);
            }
            case "tools/call" -> {
                JsonObject params = message.getAsJsonObject("params");
                JsonArray content = new JsonArray();
                JsonObject block = new JsonObject();
                block.addProperty("type", "text");
                block.addProperty("text", "echo:" + params.get("arguments"));
                content.add(block);
                result.add("content", content);
                result.addProperty("isError", false);
            }
            case "ping" -> {
                // empty result
            }
            default -> {
                return JsonRpc.error(id, -32601, "unknown method " + method);
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        response.add("result", result);
        return response;
    }
}
