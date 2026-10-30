package dev.kualu.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.http.HttpResponse;

/** Decodes a non-streaming {@code chat/completions} JSON body into assistant text. */
final class ChatResponses {

    private ChatResponses() {
    }

    static String assistantText(HttpResponse<String> response) {
        int status = response.statusCode();
        String raw = response.body() == null ? "" : response.body();

        if (status < 200 || status >= 300) {
            throw new LlmException(LlmErrorMessages.forHttp(status, raw), status, raw);
        }

        JsonObject root = parseObject(raw);

        JsonElement error = root.get("error");
        if (error != null && !error.isJsonNull()) {
            throw new LlmException(LlmErrorMessages.fromApiError(error), status, raw);
        }

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("LLM response contained no choices", status, raw);
        }

        JsonElement message = choices.get(0).getAsJsonObject().get("message");
        if (message == null || !message.isJsonObject()) {
            throw new LlmException("LLM response choice had no message object", status, raw);
        }

        JsonElement content = message.getAsJsonObject().get("content");
        if (content == null || content.isJsonNull()) {
            throw new LlmException("LLM response message had no content", status, raw);
        }
        if (!content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
            throw new LlmException("LLM response message content was not text", status, raw);
        }
        return content.getAsString();
    }

    private static JsonObject parseObject(String raw) {
        if (raw.isBlank()) {
            throw new LlmException("LLM response body was empty");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (JsonSyntaxException e) {
            throw new LlmException("LLM response was not valid JSON", e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new LlmException("LLM response was not a JSON object");
        }
        return parsed.getAsJsonObject();
    }
}
