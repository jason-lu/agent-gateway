package dev.kualu.llm.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Formats HTTP and API error payloads into readable {@link LlmException} messages. */
final class LlmErrorMessages {

    private LlmErrorMessages() {
    }

    static String forHttp(int status, String body) {
        String base = "LLM request failed with HTTP " + status + describeStatus(status);
        try {
            JsonElement parsed = JsonParser.parseString(body.isBlank() ? "null" : body);
            if (parsed != null && parsed.isJsonObject()) {
                JsonElement error = parsed.getAsJsonObject().get("error");
                if (error != null && !error.isJsonNull()) {
                    return base + ": " + fromApiError(error);
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to status-based message
        }
        return base;
    }

    static String fromApiError(JsonElement error) {
        if (error.isJsonObject()) {
            JsonObject obj = error.getAsJsonObject();
            String message = firstString(obj, "message", "msg", "detail");
            String type = firstString(obj, "type", "code");
            if (message != null && type != null) {
                return message + " (" + type + ")";
            }
            if (message != null) {
                return message;
            }
        } else if (error.isJsonPrimitive()) {
            return error.getAsString();
        }
        return "LLM API returned an error";
    }

    private static String firstString(JsonObject obj, String... names) {
        for (String name : names) {
            JsonElement value = obj.get(name);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String describeStatus(int status) {
        return switch (status) {
            case 400 -> " (bad request)";
            case 401 -> " (unauthorized - check apiKey)";
            case 403 -> " (forbidden)";
            case 404 -> " (not found - check baseUrl/model)";
            case 408 -> " (request timeout)";
            case 429 -> " (rate limited)";
            case 500 -> " (internal server error)";
            case 502 -> " (bad gateway)";
            case 503 -> " (service unavailable)";
            default -> "";
        };
    }
}
