package dev.kualu.mcp.auth;

import java.util.Map;

/**
 * Authentication strategy for HTTP MCP transports. Implementations inject
 * credentials into outbound headers and may react to {@code 401}/{@code 403}
 * challenges by refreshing state before a retry.
 */
public interface McpAuth {

    /** No credentials (open or already-authenticated endpoints). */
    static McpAuth none() {
        return headers -> {
        };
    }

    /** Adds credential headers (e.g. {@code Authorization}) to a request. */
    void apply(Map<String, String> headers);

    /**
     * Called when the server rejects the request with {@code 401} or {@code 403}.
     * Returns {@code true} when credentials were refreshed and the request should
     * be retried once.
     */
    default boolean onUnauthorized(int status, Map<String, String> responseHeaders) {
        return false;
    }
}
