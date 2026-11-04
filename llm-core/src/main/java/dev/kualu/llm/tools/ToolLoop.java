package dev.kualu.llm.tools;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.ToolSpec;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.memory.ConversationMemory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs the tool-calling loop: ask the model, execute any requested tools, feed the
 * results back, and repeat until the model answers with text.
 *
 * <p>Memory contract: on success the memory gains the user turn, every assistant
 * tool-call turn with its tool results, and the final assistant text. On failure,
 * whatever actually happened is kept (the user turn, completed tool rounds, and any
 * partially streamed text); a run that produced nothing leaves the memory untouched.
 */
public final class ToolLoop {

    /** Upper bound on model calls per run; each round may request and execute tools. */
    public static final int DEFAULT_MAX_ROUNDS = 8;

    private final LlmClient client;
    private final Map<String, AgentTool> toolsByName;
    private final List<ToolSpec> specs;
    private final int maxRounds;

    public ToolLoop(LlmClient client, List<AgentTool> tools) {
        this(client, tools, DEFAULT_MAX_ROUNDS);
    }

    /**
     * @param maxRounds maximum model calls per run before giving up
     * @throws IllegalArgumentException if {@code maxRounds < 1} or two tools share a name
     */
    public ToolLoop(LlmClient client, List<AgentTool> tools, int maxRounds) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(tools, "tools");
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1 (got " + maxRounds + ")");
        }
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.name());
            }
        }
        this.toolsByName = Map.copyOf(byName);
        this.specs = tools.stream().map(ToolSpec::of).toList();
        this.maxRounds = maxRounds;
    }

    /**
     * Runs one user turn without streaming. The reply text (everything the model
     * said across tool rounds) is returned.
     */
    public String run(ConversationMemory memory, String userContent) {
        return run(memory, userContent, null, false);
    }

    /**
     * Runs one user turn, streaming text deltas to {@code onDelta} as they arrive
     * (across tool rounds). The reply text (everything the model said across tool
     * rounds) is returned.
     */
    public String run(ConversationMemory memory, String userContent, Consumer<String> onDelta) {
        return run(memory, userContent, Objects.requireNonNull(onDelta, "onDelta"), true);
    }

    private String run(ConversationMemory memory, String userContent, Consumer<String> onDelta, boolean stream) {
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(userContent, "userContent");

        List<ChatMessage> payload = new ArrayList<>(memory.context());
        payload.add(ChatMessage.user(userContent));
        List<ChatMessage> turns = new ArrayList<>();
        StringBuilder reply = new StringBuilder();
        StringBuilder streamed = new StringBuilder();

        try {
            for (int round = 0; round < maxRounds; round++) {
                streamed.setLength(0);
                AssistantTurn turn = ask(payload, delta -> {
                    streamed.append(delta);
                    if (onDelta != null) {
                        onDelta.accept(delta);
                    }
                }, stream);
                reply.append(turn.content());

                if (!turn.hasToolCalls()) {
                    turns.add(ChatMessage.assistant(turn.content()));
                    commit(memory, userContent, turns);
                    return reply.toString();
                }

                ChatMessage assistant = ChatMessage.assistantToolCalls(turn.content(), turn.toolCalls());
                turns.add(assistant);
                payload.add(assistant);
                for (ToolCall call : turn.toolCalls()) {
                    ChatMessage result = ChatMessage.tool(executeTool(call), call.id());
                    turns.add(result);
                    payload.add(result);
                }
            }
            streamed.setLength(0);
            throw new LlmException("tool-call loop exceeded " + maxRounds + " rounds");
        } catch (RuntimeException ex) {
            if (!streamed.isEmpty()) {
                turns.add(ChatMessage.assistant(streamed.toString()));
            }
            if (!turns.isEmpty()) {
                commit(memory, userContent, turns);
            }
            throw ex;
        }
    }

    /**
     * With no tools configured the plain text path is used, which is identical on
     * the wire and cannot return tool calls. With tools, the request carries the
     * definitions and the reply is parsed for {@code tool_calls}.
     */
    private AssistantTurn ask(List<ChatMessage> payload, Consumer<String> onDelta, boolean stream) {
        if (specs.isEmpty()) {
            String text = stream ? client.chatStream(payload, onDelta) : client.chat(payload);
            return new AssistantTurn(text, List.of());
        }
        return stream
                ? client.completeStream(payload, specs, onDelta)
                : client.complete(payload, specs);
    }

    /** Expected failures are returned as result text so the model can react; nothing escapes. */
    private String executeTool(ToolCall call) {
        AgentTool tool = toolsByName.get(call.name());
        if (tool == null) {
            return "error: unknown tool '" + call.name() + "'";
        }
        try {
            String result = tool.execute(call.arguments());
            return result == null ? "" : result;
        } catch (Exception ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return "error: tool '" + call.name() + "' failed: " + reason;
        }
    }

    private static void commit(ConversationMemory memory, String userContent, List<ChatMessage> turns) {
        List<ChatMessage> next = new ArrayList<>(memory.messages());
        next.add(ChatMessage.user(userContent));
        next.addAll(turns);
        memory.replaceAll(next);
    }
}
