package dev.kualu.web.api;

import java.util.List;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record LoginResponse(String token, String tokenType, long expiresIn, String refreshToken) {
    }

    public record CreateSessionRequest(String systemPrompt) {
    }

    public record SessionResponse(String id, String owner, String createdAt, int messageCount) {
    }

    public record MessageResponse(String reply) {
    }

    public record HistoryMessage(String role, String content) {
    }

    public record HealthResponse(String status, boolean llmConfigured, boolean dbUp) {
    }

    public record AdminUserResponse(String username, List<String> roles) {
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
