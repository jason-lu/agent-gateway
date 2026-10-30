package dev.kualu.web.session;

import dev.kualu.llm.ConversationMemory;

import java.time.Instant;
import java.util.Objects;

/**
 * A conversation session owned by a single authenticated user.
 */
public final class Session {

    private final String id;
    private final String owner;
    private final Instant createdAt;
    private final ConversationMemory memory;

    public Session(String id, String owner, Instant createdAt, ConversationMemory memory) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    public String id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ConversationMemory memory() {
        return memory;
    }

    public int messageCount() {
        synchronized (memory) {
            return memory.size();
        }
    }
}
