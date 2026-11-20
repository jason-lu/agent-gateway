package dev.kualu.web.tools;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process map of owner → key/value secrets. Only registered when {@code agent.store=memory}. */
public class InMemoryUserToolEnvStore implements UserToolEnvStore {

    private final Map<String, Map<String, String>> byOwner = new ConcurrentHashMap<>();

    @Override
    public Map<String, String> listByOwner(String owner) {
        Map<String, String> entries = byOwner.get(owner);
        return entries == null ? Map.of() : new TreeMap<>(entries);
    }

    @Override
    public Optional<String> find(String owner, String key) {
        Map<String, String> entries = byOwner.get(owner);
        return entries == null ? Optional.empty() : Optional.ofNullable(entries.get(key));
    }

    @Override
    public void save(String owner, String key, String value) {
        byOwner.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public boolean delete(String owner, String key) {
        Map<String, String> entries = byOwner.get(owner);
        return entries != null && entries.remove(key) != null;
    }
}
