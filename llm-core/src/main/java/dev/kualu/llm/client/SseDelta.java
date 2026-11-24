package dev.kualu.llm.client;

import dev.kualu.llm.TokenUsage;

import java.util.List;

/**
 * One streamed SSE content delta: a text chunk, tool-call fragments, usage, or any
 * mix of them (some backends put {@code usage} on the final empty-choices chunk).
 *
 * @param text      text delta; {@code null} when the event carries none
 * @param fragments tool-call fragments; empty when the event carries none
 * @param usage     token accounting; {@code null} except on chunks that report it
 */
record SseDelta(String text, List<ToolCallFragment> fragments, TokenUsage usage) {

    SseDelta {
        fragments = fragments == null ? List.of() : List.copyOf(fragments);
    }

    SseDelta(String text, List<ToolCallFragment> fragments) {
        this(text, fragments, null);
    }

    boolean isEmpty() {
        return text == null && fragments.isEmpty() && usage == null;
    }
}
