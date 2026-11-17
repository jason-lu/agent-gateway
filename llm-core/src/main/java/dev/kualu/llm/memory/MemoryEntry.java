package dev.kualu.llm.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * One semantic-memory entry: a stable fact or preference about the user.
 * Free text — anything that looks like a hard identifier is rejected at the
 * write path and never stored.
 */
public record MemoryEntry(UUID id, String owner, Kind kind, String content, Source source,
                          String sourceSessionId, Instant createdAt, Instant updatedAt) {

    public enum Kind { FACT, PREFERENCE }

    public enum Source { EXPLICIT, AUTO, CONFIRM }
}
