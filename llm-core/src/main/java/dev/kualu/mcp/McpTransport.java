package dev.kualu.mcp;

import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * A JSON-RPC message pipe to one MCP server. Implementations deliver every inbound
 * message (responses, notifications, server-initiated requests) to the consumer and
 * write outbound messages as they are sent.
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Starts delivering inbound messages. {@code onClosed} runs once when the
     * connection is gone (process exit, stream end) so pending requests can fail fast.
     */
    void start(Consumer<JsonObject> onMessage, Runnable onClosed);

    /** Sends one JSON-RPC message. Safe to call from multiple threads. */
    void send(JsonObject message);

    boolean isOpen();

    @Override
    void close();
}
