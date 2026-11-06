package dev.kualu.mcp;

import com.google.gson.JsonObject;
import dev.kualu.llm.AgentTool;
import dev.kualu.mcp.auth.FileTokenStore;
import dev.kualu.mcp.auth.McpAuth;
import dev.kualu.mcp.auth.McpTokenStore;
import dev.kualu.mcp.auth.OAuthConfig;
import dev.kualu.mcp.auth.OAuthMcpAuth;
import dev.kualu.mcp.auth.StaticHeadersAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Connects the configured MCP servers and exposes their tools as {@link AgentTool}s.
 * The tool list is refreshed on {@code list_changed} and periodically; a dropped
 * connection is retried with capped exponential backoff. {@link #tools()} returns
 * a snapshot — the tool loop re-reads it every turn.
 */
public final class McpConnectionManager implements AutoCloseable {

    public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(60);
    public static final int DEFAULT_OUTPUT_LIMIT = 8000;
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    private final Map<String, ServerConnection> connections = new ConcurrentHashMap<>();
    private final Function<McpServerConfig, McpTransport> transportFactory;
    private final McpTokenStore tokenStore;
    private final Duration handshakeTimeout;
    private final Duration defaultCallTimeout;
    private final int outputLimit;
    private final Duration refreshInterval;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcp-manager");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpConnectionManager(List<McpServerConfig> servers) {
        this(servers, DEFAULT_HANDSHAKE_TIMEOUT, DEFAULT_CALL_TIMEOUT, DEFAULT_OUTPUT_LIMIT,
                DEFAULT_REFRESH_INTERVAL, null, null);
    }

    public McpConnectionManager(
            List<McpServerConfig> servers,
            Duration handshakeTimeout,
            Duration defaultCallTimeout,
            int outputLimit,
            Duration refreshInterval) {
        this(servers, handshakeTimeout, defaultCallTimeout, outputLimit, refreshInterval, null, null);
    }

    /**
     * @param transportFactory builds the transport per server; {@code null} selects
     *                         stdio/http from the config (tests inject fakes)
     * @param tokenStore       OAuth token persistence; {@code null} uses the file store
     */
    public McpConnectionManager(
            List<McpServerConfig> servers,
            Duration handshakeTimeout,
            Duration defaultCallTimeout,
            int outputLimit,
            Duration refreshInterval,
            Function<McpServerConfig, McpTransport> transportFactory,
            McpTokenStore tokenStore) {
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        this.defaultCallTimeout = Objects.requireNonNull(defaultCallTimeout, "defaultCallTimeout");
        this.outputLimit = outputLimit;
        this.refreshInterval = Objects.requireNonNull(refreshInterval, "refreshInterval");
        this.tokenStore = tokenStore != null ? tokenStore : FileTokenStore.defaultStore();
        this.transportFactory = transportFactory != null ? transportFactory : this::createTransport;
        for (McpServerConfig server : servers) {
            if (!server.enabled()) {
                log.info("mcp server disabled by config: {}", server.name());
                continue;
            }
            if (connections.putIfAbsent(server.name(), new ServerConnection(server)) != null) {
                throw new IllegalArgumentException("duplicate mcp server name: " + server.name());
            }
        }
    }

    private McpTransport createTransport(McpServerConfig server) {
        return switch (server.transport()) {
            case STDIO -> new StdioTransport(server.command(), server.env());
            case HTTP -> new StreamableHttpTransport(server.url(), authFor(server));
        };
    }

    private McpAuth authFor(McpServerConfig server) {
        McpServerConfig.AuthMode mode = server.authMode();
        boolean useHeaders = mode == McpServerConfig.AuthMode.HEADERS
                || (mode == McpServerConfig.AuthMode.AUTO && !server.headers().isEmpty());
        if (useHeaders) {
            return new StaticHeadersAuth(server.headers());
        }
        OAuthConfig oauth = OAuthConfig.DEFAULT
                .withClientId(server.clientId())
                .withScopes(server.scopes());
        return new OAuthMcpAuth(server.name(), server.url(), tokenStore, oauth);
    }

    /** Connects every configured server; failures log a warning and schedule a retry. */
    public void start() {
        for (ServerConnection connection : connections.values()) {
            connect(connection);
        }
        if (!refreshInterval.isZero() && !refreshInterval.isNegative()) {
            long period = refreshInterval.toMillis();
            scheduler.scheduleAtFixedRate(this::refreshAll, period, period, TimeUnit.MILLISECONDS);
        }
    }

    /** Snapshot of every tool from currently connected servers. */
    public List<AgentTool> tools() {
        return toolsExcluding(Set.of());
    }

    /** Snapshot of every tool from currently connected servers, minus the named servers. */
    public List<AgentTool> toolsExcluding(Set<String> excludedServers) {
        List<AgentTool> tools = new ArrayList<>();
        for (Map.Entry<String, ServerConnection> entry : connections.entrySet()) {
            if (excludedServers.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue().connected()) {
                tools.addAll(entry.getValue().tools);
            }
        }
        return List.copyOf(tools);
    }

    /** Server name → connection up? Covers every configured server, connected or not. */
    public Map<String, Boolean> serverStates() {
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (Map.Entry<String, ServerConnection> entry : connections.entrySet()) {
            states.put(entry.getKey(), entry.getValue().connected());
        }
        return Collections.unmodifiableMap(states);
    }

    private void connect(ServerConnection connection) {
        if (closed.get()) {
            return;
        }
        try {
            McpSession session = new McpSession(transportFactory.apply(connection.config));
            session.open(handshakeTimeout);
            connection.attach(session);
            session.onNotification("notifications/tools/list_changed",
                    params -> refreshTools(connection));
            session.onClose(() -> connectionDropped(connection));
            refreshTools(connection);
            log.info("mcp server connected: {} ({}), {} tool(s)",
                    connection.config.name(), connection.config.transportLabel(), connection.tools.size());
        } catch (RuntimeException ex) {
            log.warn("mcp server {} unavailable: {}; retrying with backoff",
                    connection.config.name(), ex.getMessage());
            connection.markDown();
            scheduleReconnect(connection);
        }
    }

    private void connectionDropped(ServerConnection connection) {
        if (closed.get()) {
            return;
        }
        connection.markDown();
        log.warn("mcp server {} connection lost; retrying with backoff", connection.config.name());
        scheduleReconnect(connection);
    }

    private void scheduleReconnect(ServerConnection connection) {
        if (closed.get()) {
            return;
        }
        int attempt = connection.attempts.incrementAndGet();
        Duration delay = backoff(attempt);
        ScheduledFuture<?> task = scheduler.schedule(() -> connect(connection),
                delay.toMillis(), TimeUnit.MILLISECONDS);
        connection.reconnectTask = task;
    }

    static Duration backoff(int attempt) {
        long seconds = 1L << Math.min(attempt - 1, 10);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.getSeconds()));
    }

    private void refreshAll() {
        for (ServerConnection connection : connections.values()) {
            if (connection.connected()) {
                refreshTools(connection);
            }
        }
    }

    private void refreshTools(ServerConnection connection) {
        try {
            List<McpRemoteTool> remote = connection.listTools(callTimeout(connection));
            List<McpToolAdapter> adapters = new ArrayList<>(remote.size());
            for (McpRemoteTool tool : remote) {
                adapters.add(new McpToolAdapter(
                        connection.config.name(), tool, connection::call,
                        callTimeout(connection), outputLimit));
            }
            connection.tools = List.copyOf(adapters);
        } catch (RuntimeException ex) {
            log.warn("mcp tools/list failed for {}: {}", connection.config.name(), ex.getMessage());
        }
    }

    private Duration callTimeout(ServerConnection connection) {
        Duration timeout = connection.config.callTimeout();
        return timeout != null ? timeout : defaultCallTimeout;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ServerConnection connection : connections.values()) {
            ScheduledFuture<?> task = connection.reconnectTask;
            if (task != null) {
                task.cancel(false);
            }
            McpSession session = connection.session;
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException ex) {
                    log.debug("mcp session close failed for {}: {}", connection.config.name(), ex.getMessage());
                }
            }
        }
        scheduler.shutdownNow();
    }

    private final class ServerConnection {

        final McpServerConfig config;
        final AtomicInteger attempts = new AtomicInteger();
        volatile McpSession session;
        volatile List<McpToolAdapter> tools = List.of();
        volatile ScheduledFuture<?> reconnectTask;

        ServerConnection(McpServerConfig config) {
            this.config = config;
        }

        void attach(McpSession session) {
            this.session = session;
            this.tools = List.of();
            attempts.set(0);
        }

        void markDown() {
            this.session = null;
            this.tools = List.of();
        }

        boolean connected() {
            McpSession current = session;
            return current != null && current.isOpen();
        }

        McpCallResult call(String name, JsonObject arguments, Duration timeout) {
            McpSession current = session;
            if (current == null) {
                throw new McpException("mcp server '" + config.name() + "' is not connected");
            }
            return current.callTool(name, arguments, timeout);
        }

        List<McpRemoteTool> listTools(Duration timeout) {
            McpSession current = session;
            if (current == null) {
                throw new McpException("mcp server '" + config.name() + "' is not connected");
            }
            return current.listTools(timeout);
        }
    }
}
