package dev.kualu.web.mcp;

import dev.kualu.llm.AgentTool;
import dev.kualu.mcp.McpConnectionManager;
import dev.kualu.mcp.McpServerConfig;
import dev.kualu.web.config.McpProperties;
import dev.kualu.web.tools.UserToolEnvStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-owner MCP connection managers. Each user's own server set — credentials
 * included — lives in its own connection pool, so one user's tools never appear in
 * another user's turn. Managers connect lazily on first use, are rebuilt when the
 * user edits their config, and are closed after an idle period. A user who binds
 * a {@code GH_TOKEN} tool credential and defines no personal {@code github} server
 * gets one synthesized from it ({@link GitHubMcp}).
 */
public class UserMcpRegistry implements AutoCloseable {

    /** A user's tool snapshot plus the server names it defines (for global overrides). */
    public record UserTools(List<AgentTool> tools, Set<String> serverNames) {
    }

    /** Per-server connection state for status displays: open session and tool count. */
    public record ServerState(boolean connected, int toolCount) {
    }

    private static final Logger log = LoggerFactory.getLogger(UserMcpRegistry.class);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);
    /** Bound on the first-turn wait for connection attempts (they run in parallel). */
    private static final Duration INITIAL_CONNECT_WAIT =
            McpConnectionManager.DEFAULT_HANDSHAKE_TIMEOUT.plusSeconds(2);

    private final UserMcpServerStore store;
    private final UserToolEnvStore toolEnvStore;
    private final McpProperties properties;
    private final Map<String, Entry> byOwner = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "user-mcp-sweep");
                thread.setDaemon(true);
                return thread;
            });

    public UserMcpRegistry(UserMcpServerStore store, UserToolEnvStore toolEnvStore, McpProperties properties) {
        this.store = store;
        this.toolEnvStore = toolEnvStore;
        this.properties = properties;
        long period = SWEEP_INTERVAL.toMillis();
        sweeper.scheduleAtFixedRate(this::evictIdle, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Tools from the owner's currently connected servers, plus the names of every
     * server they define (so same-named global servers can be held back). Waits
     * briefly for the first connection attempts — once per manager — so a turn
     * right after a config change sees the new tools without paying the wait on
     * every later turn.
     */
    public UserTools toolsFor(String owner) {
        Entry entry = entryFor(owner);
        entry.touch();
        if (entry.awaitedInitial.compareAndSet(false, true)) {
            entry.manager.awaitInitialConnect(INITIAL_CONNECT_WAIT);
        }
        Set<String> names = new HashSet<>();
        for (UserMcpServer server : entry.config) {
            if (server.enabled()) {
                names.add(server.name());
            }
        }
        return new UserTools(entry.manager.tools(), Set.copyOf(names));
    }

    /**
     * Connection state per configured server name for this owner. Never blocks on
     * handshakes: while the first attempts are still in flight every server reports
     * {@code null} (the API layer shows 待连接) and the caller can poll for the
     * settled state.
     */
    public Map<String, ServerState> connectionStates(String owner) {
        Entry entry = entryFor(owner);
        entry.touch();
        if (!entry.manager.awaitInitialConnect(Duration.ZERO)) {
            return Map.of();
        }
        Map<String, Boolean> connected = entry.manager.serverStates();
        Map<String, Integer> toolCounts = entry.manager.serverToolCounts();
        Map<String, ServerState> states = new LinkedHashMap<>();
        for (String name : connected.keySet()) {
            states.put(name, new ServerState(connected.get(name), toolCounts.getOrDefault(name, 0)));
        }
        return Collections.unmodifiableMap(states);
    }

    /** Drops the owner's manager; the next turn rebuilds it from the store. */
    public synchronized void invalidate(String owner) {
        Entry entry = byOwner.remove(owner);
        if (entry != null) {
            entry.manager.close();
        }
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        for (String owner : byOwner.keySet()) {
            invalidate(owner);
        }
    }

    private synchronized void evictIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_TIMEOUT.toMillis();
        for (Map.Entry<String, Entry> mapEntry : byOwner.entrySet()) {
            Entry entry = mapEntry.getValue();
            if (entry.lastUsedMillis < cutoff && byOwner.remove(mapEntry.getKey(), entry)) {
                entry.manager.close();
                log.info("user mcp manager evicted after idle: owner={}", mapEntry.getKey());
            }
        }
    }

    /** Synchronized so concurrent callers never build (and connect) two managers for one owner. */
    private synchronized Entry entryFor(String owner) {
        List<UserMcpServer> config = effectiveConfig(owner);
        Entry current = byOwner.get(owner);
        if (current != null && current.config.equals(config)) {
            return current;
        }
        Entry rebuilt = new Entry(config, newManager(config));
        Entry previous = byOwner.put(owner, rebuilt);
        if (previous != null) {
            previous.manager.close();
        }
        return rebuilt;
    }

    /**
     * Stored servers plus the synthesized GitHub server when the owner binds a
     * token. Part of the cache key: rotating the token rebuilds the manager.
     * Package-private for tests.
     */
    List<UserMcpServer> effectiveConfig(String owner) {
        List<UserMcpServer> stored = store.listByOwner(owner);
        Set<String> defined = new HashSet<>();
        for (UserMcpServer server : stored) {
            defined.add(server.name());
        }
        List<UserMcpServer> config = new ArrayList<>(stored);
        GitHubMcp.synthesize(toolEnvStore.listByOwner(owner), defined).ifPresent(config::add);
        return config;
    }

    private McpConnectionManager newManager(List<UserMcpServer> config) {
        List<McpServerConfig> servers = new ArrayList<>();
        for (UserMcpServer server : config) {
            if (server.enabled()) {
                servers.add(server.toMcpServerConfig());
            }
        }
        McpConnectionManager manager = new McpConnectionManager(
                servers,
                McpConnectionManager.DEFAULT_HANDSHAKE_TIMEOUT,
                Duration.ofSeconds(properties.defaultTimeoutSeconds()),
                properties.outputLimit(),
                Duration.ofSeconds(properties.refreshSeconds()));
        // Background warm-up: listing connection states must never wait on handshakes.
        manager.startAsync();
        return manager;
    }

    private static final class Entry {

        final List<UserMcpServer> config;
        final McpConnectionManager manager;
        /** The first turn after a rebuild waits for warm-up; later turns don't. */
        final AtomicBoolean awaitedInitial = new AtomicBoolean();
        volatile long lastUsedMillis = System.currentTimeMillis();

        Entry(List<UserMcpServer> config, McpConnectionManager manager) {
            this.config = config;
            this.manager = manager;
        }

        void touch() {
            lastUsedMillis = System.currentTimeMillis();
        }
    }
}
