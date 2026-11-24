package dev.kualu.llm;

import java.util.List;

/**
 * One completed assistant turn: the reply text plus any tool calls the model requested.
 *
 * @param content  reply text; empty when the model only requested tools
 * @param toolCalls requested tool calls; empty for a plain text reply
 * @param usage    tokens this call consumed, or {@code null} when the upstream reported none
 */
public record AssistantTurn(String content, List<ToolCall> toolCalls, TokenUsage usage) {

    public AssistantTurn {
        if (content == null) {
            content = "";
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** Turn without usage accounting (mocks, decoded turns). */
    public AssistantTurn(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null);
    }

    /** True when the model asked for one or more tool executions instead of answering. */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
