package dev.kualu.web.mcp;

import dev.kualu.llm.AgentTool;
import dev.kualu.mcp.McpConnectionManager;
import dev.kualu.mcp.McpServerConfig;
import dev.kualu.web.config.McpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-owner MCP connection managers. Each user's own server set — credentials
 * included — lives in its own connection pool, so one user's tools never appear in
 * another user's turn. Managers connect lazily on first use, are rebuilt when the
 * user edits their config, and are closed after an idle period.
 */
public class UserMcpRegistry implements AutoCloseable {

    /** A user's tool snapshot plus the server names it defines (for global overrides). */
    public record UserTools(List<AgentTool> tools, Set<String> serverNames) {
    }

    private static final Logger log = LoggerFactory.getLogger(UserMcpRegistry.class);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

    private final UserMcpServerStore store;
    private final McpProperties properties;
    private final Map<String, Entry> byOwner = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "user-mcp-sweep");
                thread.setDaemon(true);
                return thread;
            });

    public UserMcpRegistry(UserMcpServerStore store, McpProperties properties) {
        this.store = store;
        this.properties = properties;
        long period = SWEEP_INTERVAL.toMillis();
        sweeper.scheduleAtFixedRate(this::evictIdle, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Tools from the owner's currently connected servers, plus the names of every
     * server they define (so same-named global servers can be held back).
     */
    public UserTools toolsFor(String owner) {
        Entry entry = entryFor(owner);
        entry.touch();
        Set<String> names = new HashSet<>();
        for (UserMcpServer server : entry.config) {
            if (server.enabled()) {
                names.add(server.name());
            }
        }
        return new UserTools(entry.manager.tools(), Set.copyOf(names));
    }

    /** Connection state per configured server name for this owner. */
    public Map<String, Boolean> connectionStates(String owner) {
        Entry entry = entryFor(owner);
        entry.touch();
        return entry.manager.serverStates();
    }

    /** Drops the owner's manager; the next turn rebuilds it from the store. */
    public void invalidate(String owner) {
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

    private void evictIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_TIMEOUT.toMillis();
        for (Map.Entry<String, Entry> mapEntry : byOwner.entrySet()) {
            Entry entry = mapEntry.getValue();
            if (entry.lastUsedMillis < cutoff && byOwner.remove(mapEntry.getKey(), entry)) {
                entry.manager.close();
                log.info("user mcp manager evicted after idle: owner={}", mapEntry.getKey());
            }
        }
    }

    private Entry entryFor(String owner) {
        List<UserMcpServer> config = store.listByOwner(owner);
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
        manager.start();
        return manager;
    }

    private static final class Entry {

        final List<UserMcpServer> config;
        final McpConnectionManager manager;
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
