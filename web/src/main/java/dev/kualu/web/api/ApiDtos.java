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

    /** One tool call on an assistant turn, as stored ({@code tool_calls[]} entry). */
    public record RawToolCall(String id, String name, String arguments) {
    }

    /**
     * One stored turn in raw form for debug exports: every role including system
     * prompts, memory-compression summaries (their content carries the
     * {@code MemoryCompressor.SUMMARY_PREFIX} marker), and tool traffic. Images are
     * returned as stored — {@code ref:<sessionId>/<imageId>} for externalised
     * pixels, untouched data URIs otherwise.
     */
    public record RawMessage(String role, String content, String toolCallId, List<RawToolCall> toolCalls,
            List<String> images) {
    }

    public record HealthResponse(String status, boolean llmConfigured, boolean dbUp) {
    }

    public record AdminUserResponse(String username, List<String> roles) {
    }

    /**
     * One admin-configured LLM provider: a connection (base URL + API key) and the
     * model IDs it offers. {@code apiKey} is masked ({@code SettingsController.MASK})
     * — the real key never leaves the server. {@code primary} marks the deployment
     * default used by users who have not picked a model.
     */
    public record LlmProviderResponse(
            String id,
            String name,
            String baseUrl,
            String apiKey,
            List<String> models,
            String defaultModel,
            boolean primary) {
    }

    /**
     * Create/update body for a provider. {@code apiKey} follows the mask convention:
     * {@code SettingsController.MASK} (or a blank) keeps the stored key on update,
     * anything else replaces it; create needs a real key.
     */
    public record LlmProviderBody(
            String name,
            String baseUrl,
            String apiKey,
            List<String> models,
            String defaultModel) {
    }

    /**
     * Draft connection for a model probe: {@code id} optionally anchors the merge to a
     * stored provider (so the mask/blank keeps its key), and the remaining fields are
     * what the admin form currently holds.
     */
    public record LlmProviderProbeBody(String id, String baseUrl, String apiKey) {
    }

    /**
     * Model IDs detected on the probed upstream (OpenAI-compatible {@code GET /models});
     * feeds the admin model list so names need no manual entry.
     */
    public record AdminLlmModelsResponse(List<String> models) {
    }

    /** One pickable model in the chat window: which provider offers it and under what name. */
    public record LlmModelOption(String providerId, String providerName, String model) {
    }

    /** A concrete (provider, model) choice — what a user picks and what a turn uses. */
    public record LlmModelChoice(String providerId, String model) {
    }

    /**
     * The chat-window model picker: every pair on offer, the deployment default
     * (primary provider's default model), and the caller's effective selection —
     * their saved choice when it is still on offer, else the default.
     */
    public record LlmModelCatalogResponse(
            List<LlmModelOption> models,
            LlmModelChoice defaultChoice,
            LlmModelChoice selected) {
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

    /**
     * Outbound SMTP account used for invite mails. {@code password} is masked
     * ({@code SettingsController.MASK}) — the real value never leaves the server.
     */
    public record EmailSettingsResponse(
            String host,
            int port,
            String username,
            String password,
            String fromAddress,
            boolean useSsl) {
    }

    /**
     * Update body for the SMTP account. {@code password} follows the mask
     * convention: {@code SettingsController.MASK} (or a blank) keeps the stored
     * password, anything else replaces it.
     */
    public record EmailSettingsBody(
            String host,
            int port,
            String username,
            String password,
            String fromAddress,
            boolean useSsl) {
    }

    /** Test-send target; proves the stored SMTP account actually works. */
    public record EmailTestBody(String to) {
    }

    /** One registration invite code and its delivery/consumption state. */
    public record InviteCodeResponse(
            String code,
            String email,
            String note,
            String createdBy,
            String createdAt,
            String expiresAt,
            String sentAt,
            String usedBy,
            String usedAt) {
    }

    /** Mint body for an invite code; {@code validDays} null uses the default window. */
    public record InviteCodeBody(String email, String note, Integer validDays) {
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
