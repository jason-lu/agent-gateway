package dev.kualu.web.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local refresh sessions; everything is lost on restart. */
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private record Row(String id, String owner, Instant createdAt, Instant lastUsedAt, String tokenHash) {
        Entry entry() {
            return new Entry(id, owner, createdAt, lastUsedAt);
        }
    }

    private final ConcurrentHashMap<String, Row> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idByHash = new ConcurrentHashMap<>();

    @Override
    public void insert(Entry entry, String tokenHash) {
        byId.put(entry.id(), new Row(entry.id(), entry.owner(), entry.createdAt(), entry.lastUsedAt(), tokenHash));
        idByHash.put(tokenHash, entry.id());
    }

    @Override
    public Optional<Entry> findByHash(String tokenHash) {
        String id = idByHash.get(tokenHash);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public Optional<Entry> findById(String id) {
        return Optional.ofNullable(byId.get(id)).map(Row::entry);
    }

    @Override
    public boolean rotate(String id, String newTokenHash, Instant usedAt) {
        Row existing = byId.get(id);
        if (existing == null) {
            return false;
        }
        idByHash.remove(existing.tokenHash());
        idByHash.put(newTokenHash, id);
        byId.put(id, new Row(id, existing.owner(), existing.createdAt(), usedAt, newTokenHash));
        return true;
    }

    @Override
    public boolean touch(String id, Instant usedAt) {
        Row existing = byId.get(id);
        if (existing == null) {
            return false;
        }
        byId.put(id, new Row(id, existing.owner(), existing.createdAt(), usedAt, existing.tokenHash()));
        return true;
    }

    @Override
    public void deleteById(String id) {
        Row removed = byId.remove(id);
        if (removed != null) {
            idByHash.remove(removed.tokenHash());
        }
    }
}
