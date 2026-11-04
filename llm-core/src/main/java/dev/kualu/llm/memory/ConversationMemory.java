package dev.kualu.llm.memory;

import dev.kualu.llm.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * In-memory conversation history for multi-turn chat.
 *
 * <p>Stores prior system/user/assistant turns and produces the full
 * {@code messages} list to send with the next request.
 */
public final class ConversationMemory {

    private final List<ChatMessage> history = new ArrayList<>();

    /** Appends a system prompt. Typically called once at the start of a conversation. */
    public ConversationMemory addSystem(String content) {
        history.add(ChatMessage.system(content));
        return this;
    }

    /** Appends a completed user turn. */
    public ConversationMemory addUser(String content) {
        history.add(ChatMessage.user(content));
        return this;
    }

    /** Appends a completed assistant turn. */
    public ConversationMemory addAssistant(String content) {
        history.add(ChatMessage.assistant(content));
        return this;
    }

    /**
     * Appends a tool result turn.
     * {@code toolCallId} must match the assistant tool call this result answers.
     */
    public ConversationMemory addTool(String content, String toolCallId) {
        history.add(ChatMessage.tool(content, toolCallId));
        return this;
    }

    /**
     * Returns the stored history plus a new user message, without mutating this memory.
     * Use this to build the request payload, then {@link #addUser} / {@link #addAssistant}
     * only after the LLM call succeeds.
     */
    public List<ChatMessage> withUser(String content) {
        List<ChatMessage> next = new ArrayList<>(history.size() + 1);
        next.addAll(history);
        next.add(ChatMessage.user(content));
        return Collections.unmodifiableList(next);
    }

    /** Immutable view of the stored turns. */
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(List.copyOf(history));
    }

    /**
     * Replaces the stored turns wholesale (defensive copy). Used by memory
     * compression to fold older turns into a summary message.
     */
    public ConversationMemory replaceAll(List<ChatMessage> newHistory) {
        List<ChatMessage> copy = List.copyOf(newHistory);
        history.clear();
        history.addAll(copy);
        return this;
    }

    /** Number of stored turns (system + user + assistant). */
    public int size() {
        return history.size();
    }

    /** Drops all stored turns. */
    public void clear() {
        history.clear();
    }

    @Override
    public String toString() {
        return "ConversationMemory" + history;
    }
}
