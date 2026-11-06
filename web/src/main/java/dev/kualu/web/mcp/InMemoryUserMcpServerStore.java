package dev.kualu.web.mcp;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process map of owner → servers. Only registered when {@code agent.store=memory}. */
public class InMemoryUserMcpServerStore implements UserMcpServerStore {

    private final Map<String, Map<String, UserMcpServer>> byOwner = new ConcurrentHashMap<>();

    @Override
    public List<UserMcpServer> listByOwner(String owner) {
        Map<String, UserMcpServer> servers = byOwner.get(owner);
        if (servers == null) {
            return List.of();
        }
        return servers.values().stream()
                .sorted(Comparator.comparing(UserMcpServer::name))
                .toList();
    }

    @Override
    public Optional<UserMcpServer> find(String owner, String name) {
        Map<String, UserMcpServer> servers = byOwner.get(owner);
        return servers == null ? Optional.empty() : Optional.ofNullable(servers.get(name));
    }

    @Override
    public void save(String owner, UserMcpServer server) {
        byOwner.computeIfAbsent(owner, key -> new ConcurrentHashMap<>()).put(server.name(), server);
    }

    @Override
    public boolean delete(String owner, String name) {
        Map<String, UserMcpServer> servers = byOwner.get(owner);
        return servers != null && servers.remove(name) != null;
    }
}
