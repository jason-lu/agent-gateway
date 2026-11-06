package dev.kualu.web.session;

import dev.kualu.llm.memory.ConversationMemory;

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
    private volatile String title;

    public Session(String id, String owner, Instant createdAt, ConversationMemory memory) {
        this(id, owner, createdAt, null, memory);
    }

    public Session(String id, String owner, Instant createdAt, String title, ConversationMemory memory) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.title = title;
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

    /** Short human label for listings; {@code null} until derived from the conversation. */
    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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
