package dev.kualu.web.memory;

import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.UserProfile;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local semantic memory. Memory is lost on restart.
 * Only registered when {@code agent.store=memory}.
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, MemoryEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<UserProfile> findProfile(String owner) {
        return Optional.ofNullable(profiles.get(owner));
    }

    @Override
    public void saveProfile(String owner, UserProfile profile) {
        profiles.put(owner, profile);
    }

    @Override
    public void deleteProfile(String owner) {
        profiles.remove(owner);
    }

    @Override
    public List<MemoryEntry> listEntries(String owner) {
        return entries.values().stream()
                .filter(entry -> entry.owner().equals(owner))
                .sorted(Comparator.comparing(MemoryEntry::updatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<MemoryEntry> findEntry(String owner, UUID id) {
        MemoryEntry entry = entries.get(id);
        if (entry == null || !entry.owner().equals(owner)) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    @Override
    public MemoryEntry saveEntry(MemoryEntry entry) {
        Instant now = Timestamps.next();
        if (entry.id() != null) {
            MemoryEntry old = entries.get(entry.id());
            if (old != null && old.owner().equals(entry.owner())) {
                MemoryEntry updated = new MemoryEntry(old.id(), old.owner(), old.kind(), entry.content(),
                        old.source(), old.sourceSessionId(), old.createdAt(), now);
                entries.put(old.id(), updated);
                return updated;
            }
        }
        // Inserts always take a fresh id: a foreign id must not clobber another
        // owner's entry, and a stale one must not resurrect a hard-deleted entry.
        MemoryEntry stored = new MemoryEntry(UUID.randomUUID(), entry.owner(), entry.kind(), entry.content(),
                entry.source(), entry.sourceSessionId(),
                entry.createdAt() != null ? entry.createdAt() : now, now);
        entries.put(stored.id(), stored);
        return stored;
    }

    @Override
    public boolean deleteEntry(String owner, UUID id) {
        MemoryEntry entry = entries.get(id);
        if (entry == null || !entry.owner().equals(owner)) {
            return false;
        }
        return entries.remove(id, entry);
    }

    @Override
    public void clear(String owner) {
        profiles.remove(owner);
        entries.values().removeIf(entry -> entry.owner().equals(owner));
    }
}
