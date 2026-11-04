package dev.kualu.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merges streamed {@code delta.tool_calls[]} fragments into complete {@link ToolCall}s.
 * Fragments are ordered by their {@code index}; arguments arrive as concatenated chunks.
 */
final class ToolCallAccumulator {

    private static final class Builder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private final Map<Integer, Builder> byIndex = new TreeMap<>();

    void add(ToolCallFragment fragment) {
        Builder builder = byIndex.computeIfAbsent(fragment.index(), i -> new Builder());
        if (fragment.id() != null && !fragment.id().isBlank()) {
            builder.id = fragment.id();
        }
        if (fragment.name() != null && !fragment.name().isBlank()) {
            builder.name = fragment.name();
        }
        if (fragment.argumentsChunk() != null) {
            builder.arguments.append(fragment.argumentsChunk());
        }
    }

    /** Returns the assembled calls, or an empty list when no fragments arrived. */
    List<ToolCall> finish() {
        if (byIndex.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>(byIndex.size());
        for (Builder builder : byIndex.values()) {
            if (builder.id == null || builder.name == null) {
                throw new LlmException("streamed tool call was missing its id or name");
            }
            calls.add(new ToolCall(builder.id, builder.name, builder.arguments.toString()));
        }
        return List.copyOf(calls);
    }
}
