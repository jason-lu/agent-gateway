package dev.kualu.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Parses OpenAI-style SSE chat-completion events into content deltas.
 * Accepts the {@code data:} payload of a single SSE line.
 */
final class SseContentDeltas {

    private SseContentDeltas() {
    }

    static boolean isDataLine(String line) {
        return line.startsWith("data:");
    }

    static String dataOf(String line) {
        return line.substring("data:".length()).trim();
    }

    /** Returns the text delta in {@code data}, or {@code null} if the event carries none. */
    static String extract(String data) {
        if (data.isBlank()) {
            return null;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(data);
        } catch (JsonSyntaxException e) {
            throw new LlmException("LLM stream event was not valid JSON: " + data, e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return null;
        }
        JsonObject root = parsed.getAsJsonObject();

        JsonElement error = root.get("error");
        if (error != null && !error.isJsonNull()) {
            throw new LlmException("LLM stream error: " + LlmErrorMessages.fromApiError(error), 200, data);
        }

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }

        JsonElement deltaEl = choices.get(0).getAsJsonObject().get("delta");
        if (deltaEl == null || !deltaEl.isJsonObject()) {
            return null;
        }
        JsonElement content = deltaEl.getAsJsonObject().get("content");
        if (content == null || content.isJsonNull()) {
            return null;
        }
        if (!content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = content.getAsString();
        return text.isEmpty() ? null : text;
    }
}
