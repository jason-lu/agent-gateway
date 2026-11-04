package dev.kualu.llm;

import java.util.List;

/**
 * One streamed SSE content delta: a text chunk, tool-call fragments, or both
 * (some backends mix them in a single event).
 *
 * @param text      text delta; {@code null} when the event carries none
 * @param fragments tool-call fragments; empty when the event carries none
 */
record SseDelta(String text, List<ToolCallFragment> fragments) {

    SseDelta {
        fragments = fragments == null ? List.of() : List.copyOf(fragments);
    }

    boolean isEmpty() {
        return text == null && fragments.isEmpty();
    }
}
