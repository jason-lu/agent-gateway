package dev.kualu.mcp.auth;

/**
 * OAuth token pair for one MCP server.
 *
 * @param accessToken  bearer token sent to the server
 * @param refreshToken refresh grant secret, or null when the server issued none
 * @param expiresAt    access-token expiry (epoch seconds); {@code Long.MAX_VALUE} when unknown
 */
public record StoredTokens(String accessToken, String refreshToken, long expiresAt) {

    public StoredTokens {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
    }

    /** True when the access token is past expiry (with a 30s clock-skew margin). */
    public boolean isExpired(long nowEpochSeconds) {
        return nowEpochSeconds >= expiresAt - 30;
    }
}
