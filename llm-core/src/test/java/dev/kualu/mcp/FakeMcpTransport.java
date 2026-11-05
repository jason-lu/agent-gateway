package dev.kualu.mcp;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * In-memory {@link McpTransport} for tests: records outbound messages and delivers
 * scripted responses through the registered consumer.
 */
final class FakeMcpTransport implements McpTransport {

    final List<JsonObject> sent = new CopyOnWriteArrayList<>();
    /** Maps an outbound request to its response, or {@code null} to stay silent. */
    volatile Function<JsonObject, JsonObject> responder = message -> null;

    private Consumer<JsonObject> onMessage;
    private Runnable onClosed;
    private volatile boolean open = true;

    @Override
    public void start(Consumer<JsonObject> onMessage, Runnable onClosed) {
        this.onMessage = onMessage;
        this.onClosed = onClosed;
    }

    @Override
    public void send(JsonObject message) {
        if (!open) {
            throw new McpException("transport closed");
        }
        sent.add(message);
        JsonObject response = responder.apply(message);
        if (response != null && onMessage != null) {
            onMessage.accept(response);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    /** Injects a server-initiated message (notification or request). */
    void deliver(JsonObject message) {
        onMessage.accept(message);
    }

    /** Simulates a dropped connection (process exit, stream end). */
    void disconnect() {
        open = false;
        if (onClosed != null) {
            onClosed.run();
        }
    }

    JsonObject sentAt(int index) {
        return sent.get(index);
    }
}
