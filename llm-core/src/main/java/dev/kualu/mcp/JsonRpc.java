package dev.kualu.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** JSON-RPC 2.0 message shapes used by the MCP client. */
final class JsonRpc {

    static final String VERSION = "2.0";

    private JsonRpc() {
    }

    static JsonObject request(long id, String method, JsonObject params) {
        JsonObject message = base();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        return message;
    }

    static JsonObject notification(String method, JsonObject params) {
        JsonObject message = base();
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        return message;
    }

    static JsonObject response(long id, JsonElement result) {
        JsonObject message = base();
        message.addProperty("id", id);
        message.add("result", result == null ? new JsonObject() : result);
        return message;
    }

    static JsonObject error(long id, int code, String errorMessage) {
        JsonObject message = base();
        message.addProperty("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", errorMessage);
        message.add("error", error);
        return message;
    }

    static boolean isResponse(JsonObject message) {
        return message.has("id") && !message.has("method") && (message.has("result") || message.has("error"));
    }

    static boolean isRequest(JsonObject message) {
        return message.has("id") && message.has("method");
    }

    static boolean isNotification(JsonObject message) {
        return !message.has("id") && message.has("method");
    }

    /** Correlation id as a long, or {@code null} when absent or not numeric. */
    static Long id(JsonObject message) {
        JsonElement id = message.get("id");
        if (id == null || !id.isJsonPrimitive()) {
            return null;
        }
        try {
            return id.getAsLong();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static JsonObject base() {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", VERSION);
        return message;
    }
}
