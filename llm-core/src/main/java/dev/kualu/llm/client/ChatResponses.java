package dev.kualu.llm.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Decodes a non-streaming {@code chat/completions} JSON body into assistant text. */
final class ChatResponses {

    private ChatResponses() {
    }

    static String assistantText(HttpResponse<String> response) {
        JsonObject message = firstMessage(response);

        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            throw new LlmException("LLM response message had no content");
        }
        if (!content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
            throw new LlmException("LLM response message content was not text");
        }
        return content.getAsString();
    }

    /** Decodes the first choice into text plus any {@code tool_calls} the model requested. */
    static AssistantTurn assistantTurn(HttpResponse<String> response) {
        JsonObject message = firstMessage(response);

        JsonElement contentEl = message.get("content");
        String content = "";
        if (contentEl != null && !contentEl.isJsonNull()) {
            if (!contentEl.isJsonPrimitive() || !contentEl.getAsJsonPrimitive().isString()) {
                throw new LlmException("LLM response message content was not text");
            }
            content = contentEl.getAsString();
        }

        List<ToolCall> toolCalls = parseToolCalls(message.get("tool_calls"));
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new LlmException("LLM response message had no content");
        }
        return new AssistantTurn(content, toolCalls);
    }

    private static JsonObject firstMessage(HttpResponse<String> response) {
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
        return message.getAsJsonObject();
    }

    static List<ToolCall> parseToolCalls(JsonElement toolCallsEl) {
        if (toolCallsEl == null || toolCallsEl.isJsonNull()) {
            return List.of();
        }
        if (!toolCallsEl.isJsonArray()) {
            throw new LlmException("LLM response message tool_calls was not an array");
        }
        JsonArray array = toolCallsEl.getAsJsonArray();
        List<ToolCall> calls = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                throw new LlmException("LLM response tool call was not an object");
            }
            JsonObject call = item.getAsJsonObject();
            String id = stringOrNull(call.get("id"));
            JsonElement functionEl = call.get("function");
            if (functionEl == null || !functionEl.isJsonObject()) {
                throw new LlmException("LLM response tool call had no function object");
            }
            JsonObject function = functionEl.getAsJsonObject();
            String name = stringOrNull(function.get("name"));
            String arguments = stringOrNull(function.get("arguments"));
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new LlmException("LLM response tool call was missing its id or name");
            }
            calls.add(new ToolCall(id, name, arguments == null ? "{}" : arguments));
        }
        return List.copyOf(calls);
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
