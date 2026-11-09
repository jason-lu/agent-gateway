package dev.kualu.web.api;

import java.util.List;
import java.util.Map;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record LoginResponse(String token, String tokenType, long expiresIn, String refreshToken) {
    }

    public record CreateSessionRequest(String systemPrompt) {
    }

    public record SessionResponse(String id, String owner, String title, String createdAt, int messageCount) {
    }

    public record MessageResponse(String reply) {
    }

    public record HistoryMessage(String role, String content, List<String> images) {
    }

    public record HealthResponse(String status, boolean llmConfigured, boolean dbUp) {
    }

    public record AdminUserResponse(String username, List<String> roles) {
    }

    /**
     * Admin LLM upstream overrides plus what is actually in effect. {@code apiKey} is
     * masked ({@code SettingsController.MASK}) - the real key never leaves the server.
     * Sources are {@code admin}, {@code env} or {@code none}; {@code configured} is
     * {@code true} when every effective field has a value.
     */
    public record AdminLlmSettingsResponse(
            String baseUrl,
            String apiKey,
            String model,
            String effectiveBaseUrl,
            String effectiveModel,
            String baseUrlSource,
            String apiKeySource,
            String modelSource,
            boolean configured) {
    }

    /**
     * Model IDs detected on the configured LLM upstream (OpenAI-compatible
     * {@code GET /models}); feeds the admin dropdown so the model name needs no
     * manual entry.
     */
    public record AdminLlmModelsResponse(List<String> models) {
    }

    /**
     * One user-configured MCP server. {@code env}/{@code headers} values are masked
     * ({@code ••••••••}) — real credentials never leave the server. {@code status} is
     * {@code connected}, {@code disconnected}, {@code disabled} or {@code unknown}.
     */
    public record McpServerResponse(
            String name,
            String transport,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            Map<String, String> headers,
            boolean enabled,
            String status) {
    }

    public record ErrorResponse(int status, String error, String message) {
    }

    public record SseDelta(String text) {
    }

    public record SseDone(String reply) {
    }

    public record SseError(String message) {
    }
}
