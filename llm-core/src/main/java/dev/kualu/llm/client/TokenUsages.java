package dev.kualu.llm.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.kualu.llm.TokenUsage;

/**
 * Decodes the OpenAI-style {@code usage} block into {@link TokenUsage}.
 * Tolerant by design: every field is optional ({@code 0} when absent), and a
 * missing/blank block yields {@code null} — "not reported", not "zero".
 */
final class TokenUsages {

    private TokenUsages() {
    }

    /** @return the parsed usage, or {@code null} when {@code element} carries none */
    static TokenUsage fromJson(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonObject usage = element.getAsJsonObject();
        TokenUsage parsed = new TokenUsage(
                intOf(usage, "prompt_tokens"),
                intOf(usage, "completion_tokens"),
                intOf(usage, "total_tokens"),
                intOf(details(usage, "completion_tokens_details"), "reasoning_tokens"),
                intOf(details(usage, "prompt_tokens_details"), "cached_tokens"));
        return parsed.isEmpty() ? null : parsed;
    }

    private static JsonObject details(JsonObject usage, String name) {
        JsonElement details = usage.get(name);
        return details != null && details.isJsonObject() ? details.getAsJsonObject() : null;
    }

    private static int intOf(JsonObject object, String name) {
        if (object == null) {
            return 0;
        }
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return value.getAsInt();
    }
}
