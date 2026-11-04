package dev.kualu.llm.client;

/**
 * One streamed {@code delta.tool_calls[]} fragment. Later chunks carry only
 * {@code argumentsChunk}; {@code id} and {@code name} arrive with the first
 * fragment of each call.
 *
 * @param index         position of the call in the turn's {@code tool_calls} array
 * @param id            call id ({@code id}); {@code null} on continuation chunks
 * @param name          function name; {@code null} on continuation chunks
 * @param argumentsChunk next chunk of the arguments JSON string; {@code null} when absent
 */
record ToolCallFragment(int index, String id, String name, String argumentsChunk) {
}
