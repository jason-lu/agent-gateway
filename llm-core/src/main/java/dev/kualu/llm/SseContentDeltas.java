package dev.kualu.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

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

    /** Returns the delta carried by {@code data}, or {@code null} if the event carries none. */
    static SseDelta extract(String data) {
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
        JsonObject delta = deltaEl.getAsJsonObject();

        String text = null;
        JsonElement content = delta.get("content");
        if (content != null && !content.isJsonNull()
                && content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
            text = content.getAsString();
            if (text.isEmpty()) {
                text = null;
            }
        }

        List<ToolCallFragment> fragments = parseFragments(delta.get("tool_calls"));
        if (text == null && fragments.isEmpty()) {
            return null;
        }
        return new SseDelta(text, fragments);
    }

    private static List<ToolCallFragment> parseFragments(JsonElement toolCallsEl) {
        if (toolCallsEl == null || toolCallsEl.isJsonNull()) {
            return List.of();
        }
        if (!toolCallsEl.isJsonArray()) {
            throw new LlmException("LLM stream event tool_calls was not an array");
        }
        JsonArray array = toolCallsEl.getAsJsonArray();
        List<ToolCallFragment> fragments = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject call = item.getAsJsonObject();
            int index = 0;
            JsonElement indexEl = call.get("index");
            if (indexEl != null && indexEl.isJsonPrimitive() && indexEl.getAsJsonPrimitive().isNumber()) {
                index = indexEl.getAsInt();
            }
            String id = stringOrNull(call.get("id"));
            String name = null;
            String argumentsChunk = null;
            JsonElement functionEl = call.get("function");
            if (functionEl != null && functionEl.isJsonObject()) {
                JsonObject function = functionEl.getAsJsonObject();
                name = stringOrNull(function.get("name"));
                argumentsChunk = stringOrNull(function.get("arguments"));
            }
            fragments.add(new ToolCallFragment(index, id, name, argumentsChunk));
        }
        return List.copyOf(fragments);
    }

    private static String stringOrNull(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }
}
