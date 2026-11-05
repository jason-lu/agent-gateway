package dev.kualu.mcp;

/**
 * The server rejected the request because the MCP session is gone ({@code 404}
 * with a session id). The session layer re-handshakes and retries the call once.
 */
public class McpSessionExpiredException extends McpException {

    public McpSessionExpiredException(String message) {
        super(message);
    }
}
