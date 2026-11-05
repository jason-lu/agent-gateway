package dev.kualu.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One JSON-RPC session with an MCP server: {@code initialize} handshake with version
 * negotiation, request/response correlation, and notification dispatch. Tool access
 * ({@code tools/list}, {@code tools/call}) is exposed directly.
 */
public final class McpSession implements AutoCloseable {

    /** Newest first; the server picks one during {@code initialize}. */
    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of("2025-06-18", "2025-03-26", "2024-11-05");

    public static final String CLIENT_NAME = "dev.kualu.agent";
    public static final String CLIENT_VERSION = "1.0";

    private static final Logger log = LoggerFactory.getLogger(McpSession.class);

    private final McpTransport transport;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<JsonObject>>> notificationHandlers = new ConcurrentHashMap<>();
    private final List<Runnable> closeHandlers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closedFired = new AtomicBoolean();

    private volatile String protocolVersion;
    private volatile JsonObject serverCapabilities = new JsonObject();
    private volatile String serverName = "unknown";
    private volatile boolean open;

    public McpSession(McpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * Starts the transport and performs the {@code initialize} handshake.
     *
     * @throws McpException when the transport fails or the server selects an
     *                      unsupported protocol version
     */
    public void open(Duration handshakeTimeout) {
        Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        transport.start(this::dispatch, this::onTransportClosed);
        open = true;
        handshake(handshakeTimeout);
    }

    /** (Re-)runs the {@code initialize} handshake on the live transport. */
    private synchronized void handshake(Duration handshakeTimeout) {
        JsonObject initParams = new JsonObject();
        initParams.addProperty("protocolVersion", SUPPORTED_PROTOCOL_VERSIONS.get(0));
        initParams.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", CLIENT_NAME);
        clientInfo.addProperty("version", CLIENT_VERSION);
        initParams.add("clientInfo", clientInfo);

        JsonObject result = doRequest("initialize", initParams, handshakeTimeout);
        String negotiated = result.has("protocolVersion") && result.get("protocolVersion").isJsonPrimitive()
                ? result.get("protocolVersion").getAsString()
                : null;
        if (negotiated == null || !SUPPORTED_PROTOCOL_VERSIONS.contains(negotiated)) {
            close();
            throw new McpException("unsupported protocol version selected by server: " + negotiated);
        }
        protocolVersion = negotiated;
        serverCapabilities = result.has("capabilities") && result.get("capabilities").isJsonObject()
                ? result.getAsJsonObject("capabilities")
                : new JsonObject();
        if (result.has("serverInfo") && result.get("serverInfo").isJsonObject()) {
            JsonObject info = result.getAsJsonObject("serverInfo");
            if (info.has("name") && info.get("name").isJsonPrimitive()) {
                serverName = info.get("name").getAsString();
            }
        }
        notify("notifications/initialized", null);
        log.info("mcp-session opened: server={}, protocolVersion={}", serverName, negotiated);
    }

    /**
     * Sends a JSON-RPC request and returns the {@code result} object. When the
     * server reports the session expired, the handshake is redone and the request
     * retried once.
     */
    public JsonObject request(String method, JsonObject params, Duration timeout) {
        try {
            return doRequest(method, params, timeout);
        } catch (McpSessionExpiredException ex) {
            log.info("mcp session expired during {}; re-handshaking and retrying once", method);
            handshake(timeout);
            return doRequest(method, params, timeout);
        }
    }

    private JsonObject doRequest(String method, JsonObject params, Duration timeout) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(timeout, "timeout");
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            transport.send(JsonRpc.request(id, method, params));
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                throw new McpException("mcp request timed out after " + timeout.toMillis() + "ms: " + method);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new McpException("mcp request interrupted: " + method, ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof McpException mcp) {
                    throw mcp;
                }
                throw new McpException("mcp request failed: " + method + ": " + cause.getMessage(), cause);
            }
        } finally {
            pending.remove(id);
        }
    }

    /** Sends a JSON-RPC notification (no response expected). */
    public void notify(String method, JsonObject params) {
        transport.send(JsonRpc.notification(method, params));
    }

    /** Liveness check ({@code ping}); returns when the server answers. */
    public void ping(Duration timeout) {
        request("ping", null, timeout);
    }

    /** Registers a handler for server notifications of the given method. */
    public void onNotification(String method, Consumer<JsonObject> handler) {
        notificationHandlers
                .computeIfAbsent(method, key -> new CopyOnWriteArrayList<>())
                .add(Objects.requireNonNull(handler, "handler"));
    }

    /** Registers a handler run once when the session ends (close or transport loss). */
    public void onClose(Runnable handler) {
        closeHandlers.add(Objects.requireNonNull(handler, "handler"));
    }

    /** Lists every tool the server advertises, following pagination cursors. */
    public List<McpRemoteTool> listTools(Duration timeout) {
        List<McpRemoteTool> tools = new ArrayList<>();
        JsonObject params = new JsonObject();
        while (true) {
            JsonObject result = request("tools/list", params, timeout);
            if (result.has("tools") && result.get("tools").isJsonArray()) {
                for (JsonElement element : result.getAsJsonArray("tools")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject tool = element.getAsJsonObject();
                    if (!tool.has("name") || !tool.get("name").isJsonPrimitive()) {
                        continue;
                    }
                    String description = tool.has("description") && tool.get("description").isJsonPrimitive()
                            ? tool.get("description").getAsString()
                            : "";
                    JsonObject schema = tool.has("inputSchema") && tool.get("inputSchema").isJsonObject()
                            ? tool.getAsJsonObject("inputSchema")
                            : new JsonObject();
                    tools.add(new McpRemoteTool(tool.get("name").getAsString(), description, schema));
                }
            }
            if (result.has("nextCursor") && result.get("nextCursor").isJsonPrimitive()) {
                params = new JsonObject();
                params.addProperty("cursor", result.get("nextCursor").getAsString());
            } else {
                break;
            }
        }
        return tools;
    }

    /** Calls a tool and returns its raw result ({@code content} + {@code isError}). */
    public McpCallResult callTool(String name, JsonObject arguments, Duration timeout) {
        JsonObject params = new JsonObject();
        params.addProperty("name", Objects.requireNonNull(name, "name"));
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        JsonObject result = request("tools/call", params, timeout);
        JsonArray content = result.has("content") && result.get("content").isJsonArray()
                ? result.getAsJsonArray("content")
                : new JsonArray();
        boolean error = result.has("isError") && result.get("isError").isJsonPrimitive()
                && result.get("isError").getAsBoolean();
        JsonObject structured = result.has("structuredContent") && result.get("structuredContent").isJsonObject()
                ? result.getAsJsonObject("structuredContent")
                : null;
        return new McpCallResult(content, error, structured);
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    public JsonObject serverCapabilities() {
        return serverCapabilities;
    }

    public String serverName() {
        return serverName;
    }

    public boolean isOpen() {
        return open && transport.isOpen();
    }

    @Override
    public void close() {
        open = false;
        try {
            transport.close();
        } catch (RuntimeException ex) {
            log.debug("mcp transport close failed: {}", ex.getMessage());
        }
        failPending("mcp session closed");
        fireClosed();
    }

    private void dispatch(JsonObject message) {
        if (JsonRpc.isResponse(message)) {
            completeResponse(message);
        } else if (JsonRpc.isRequest(message)) {
            // No server-initiated methods supported; answer so the server is not left hanging.
            Long id = JsonRpc.id(message);
            try {
                transport.send(JsonRpc.error(id == null ? 0L : id, -32601, "method not supported by client"));
            } catch (RuntimeException ex) {
                log.warn("failed to reject server-initiated request: {}", ex.getMessage());
            }
        } else if (JsonRpc.isNotification(message)) {
            String method = message.get("method").getAsString();
            JsonObject params = message.has("params") && message.get("params").isJsonObject()
                    ? message.getAsJsonObject("params")
                    : new JsonObject();
            for (Consumer<JsonObject> handler : notificationHandlers.getOrDefault(method, List.of())) {
                try {
                    handler.accept(params);
                } catch (RuntimeException ex) {
                    log.warn("mcp notification handler failed for {}: {}", method, ex.getMessage());
                }
            }
        } else {
            log.debug("ignoring unrecognized json-rpc message");
        }
    }

    private void completeResponse(JsonObject message) {
        Long id = JsonRpc.id(message);
        CompletableFuture<JsonObject> future = id == null ? null : pending.get(id);
        if (future == null) {
            log.debug("mcp response for unknown id {}", id);
            return;
        }
        if (message.has("error") && message.get("error").isJsonObject()) {
            JsonObject error = message.getAsJsonObject("error");
            int code = error.has("code") && error.get("code").isJsonPrimitive() ? error.get("code").getAsInt() : 0;
            String errorMessage = error.has("message") && error.get("message").isJsonPrimitive()
                    ? error.get("message").getAsString()
                    : "unknown error";
            future.completeExceptionally(new McpException("mcp error " + code + ": " + errorMessage));
        } else {
            future.complete(message.has("result") && message.get("result").isJsonObject()
                    ? message.getAsJsonObject("result")
                    : new JsonObject());
        }
    }

    private void onTransportClosed() {
        open = false;
        failPending("mcp transport closed");
        fireClosed();
    }

    private void fireClosed() {
        if (!closedFired.compareAndSet(false, true)) {
            return;
        }
        for (Runnable handler : closeHandlers) {
            try {
                handler.run();
            } catch (RuntimeException ex) {
                log.warn("mcp close handler failed: {}", ex.getMessage());
            }
        }
    }

    private void failPending(String reason) {
        McpException failure = new McpException(reason);
        for (CompletableFuture<JsonObject> future : pending.values()) {
            future.completeExceptionally(failure);
        }
        pending.clear();
    }
}
