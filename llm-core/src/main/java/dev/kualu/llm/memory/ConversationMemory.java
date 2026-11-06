package dev.kualu.llm.memory;

import dev.kualu.llm.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * In-memory conversation history for multi-turn chat.
 *
 * <p>Stores every system/user/assistant turn. {@link #messages()} is the full
 * history (what the user sees and what is persisted); {@link #context()} is the
 * bounded view sent to the model, where turns folded into the compression
 * summary are replaced by that summary.
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
     * Returns the request payload for the next turn — the {@link #context()}
     * view plus a new user message — without mutating this memory. Commit the
     * turn with {@link #addUser} / {@link #addAssistant} only after the LLM
     * call succeeds.
     */
    public List<ChatMessage> withUser(String content) {
        List<ChatMessage> next = new ArrayList<>(context());
        next.add(ChatMessage.user(content));
        return Collections.unmodifiableList(next);
    }

    /** Immutable view of the stored turns: the full history, including folded turns. */
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(List.copyOf(history));
    }

    /**
     * The bounded request-payload view: the leading system prompt, the
     * compression summary (if any), and the turns after the fold point. Turns
     * folded into the summary stay in {@link #messages()} but are not sent to
     * the model.
     */
    public List<ChatMessage> context() {
        int fold = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (isSummary(history.get(i))) {
                fold = i;
                break;
            }
        }
        if (fold < 0) {
            return messages();
        }
        List<ChatMessage> next = new ArrayList<>(history.size() - fold + 1);
        if (fold > 0 && isPlainSystem(history.getFirst())) {
            next.add(history.getFirst());
        }
        next.add(history.get(fold));
        next.addAll(history.subList(fold + 1, history.size()));
        return Collections.unmodifiableList(next);
    }

    private static boolean isSummary(ChatMessage message) {
        return "system".equals(message.role())
                && message.content().startsWith(MemoryCompressor.SUMMARY_PREFIX);
    }

    private static boolean isPlainSystem(ChatMessage message) {
        return "system".equals(message.role()) && !isSummary(message);
    }

    /**
     * Replaces the stored turns wholesale (defensive copy). Used by the tool
     * loop to commit a turn, by memory compression to move the fold marker,
     * and by session stores to restore a conversation.
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
