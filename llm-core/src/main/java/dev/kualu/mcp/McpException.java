package dev.kualu.mcp;

/** Failure in the MCP client layer: transport, handshake, timeout, or a JSON-RPC error. */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
