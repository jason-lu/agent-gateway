package dev.kualu.web.memory;

import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.UserProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-user semantic memory: the whitelisted personal archive (profile fields)
 * plus free-text fact/preference entries. Owner-scoped like
 * {@code SessionStore} — every lookup is scoped by owner in the store itself.
 *
 * <p>Deletes are hard deletes: cleared memory must not be recoverable from the
 * store. Hard identifiers never reach a store; callers run
 * {@code HardIdentifierDetector} before every write.
 */
public interface MemoryStore {

    Optional<UserProfile> findProfile(String owner);

    /** Upsert of the whole profile row for one owner. */
    void saveProfile(String owner, UserProfile profile);

    /** Hard-deletes the profile row; no-op when absent. */
    void deleteProfile(String owner);

    /** Newest first. */
    List<MemoryEntry> listEntries(String owner);

    Optional<MemoryEntry> findEntry(String owner, UUID id);

    /**
     * A non-null {@code entry.id()} selects an update of this owner's entry
     * (content only); inserts always generate a fresh id. A foreign or stale id
     * therefore neither clobbers another owner's entry nor resurrects a
     * hard-deleted one.
     */
    MemoryEntry saveEntry(MemoryEntry entry);

    /** Hard delete; false when the entry does not exist for this owner. */
    boolean deleteEntry(String owner, UUID id);

    /** Hard-deletes the profile row and every entry of the owner. */
    void clear(String owner);
}
