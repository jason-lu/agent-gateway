package dev.kualu.llm.tools;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.ToolSpec;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.llm.metrics.TurnTimings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs the tool-calling loop: ask the model, execute any requested tools, feed the
 * results back, and repeat until the model answers with text.
 *
 * <p>Memory contract: on success the memory gains the user turn, every assistant
 * tool-call turn with its tool results, and the final assistant text. On failure,
 * whatever actually happened is kept (the user turn, completed tool rounds, and any
 * partially streamed text); a run that produced nothing leaves the memory untouched.
 *
 * <p>The tool list may come from a {@link Supplier} and is snapshotted at the start
 * of every run, so dynamically discovered tools (e.g. MCP) can appear or disappear
 * between turns without restarting the loop. When tools are on offer, the request
 * payload leads with the standing {@link #TOOL_POLICY} system message (request-only;
 * it is never stored in the memory).</p>
 */
public final class ToolLoop {

    /** Upper bound on model calls per run; each round may request and execute tools. */
    public static final int DEFAULT_MAX_ROUNDS = 30;

    /** Name of the classpath resource holding the standing tool-use policy. */
    static final String TOOL_POLICY_RESOURCE = "tool-policy.txt";

    /**
     * Standing tool-use policy, prepended to the request when tools are on offer.
     * Kept in a resource so it can be tuned without touching code (same boundary
     * as the summarisation instruction: wording in the file, assembly in code).
     */
    public static final String TOOL_POLICY = loadToolPolicy();

    private static String loadToolPolicy() {
        try (InputStream in = ToolLoop.class.getResourceAsStream(TOOL_POLICY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource "
                        + ToolLoop.class.getPackageName().replace('.', '/') + "/" + TOOL_POLICY_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ToolLoop.class);

    private final LlmClient client;
    private final Supplier<List<AgentTool>> tools;
    private final Supplier<List<ChatMessage>> requestPreamble;
    private final int maxRounds;

    public ToolLoop(LlmClient client, List<AgentTool> tools) {
        this(client, tools, DEFAULT_MAX_ROUNDS);
    }

    /**
     * @param maxRounds maximum model calls per run before giving up
     * @throws IllegalArgumentException if {@code maxRounds < 1} or two tools share a name
     */
    public ToolLoop(LlmClient client, List<AgentTool> tools, int maxRounds) {
        this(client, fixed(tools), maxRounds);
    }

    public ToolLoop(LlmClient client, Supplier<List<AgentTool>> tools) {
        this(client, tools, DEFAULT_MAX_ROUNDS);
    }

    /** @param tools supplies the tool list; evaluated once per {@code run} */
    public ToolLoop(LlmClient client, Supplier<List<AgentTool>> tools, int maxRounds) {
        this(client, tools, () -> List.of(), maxRounds);
    }

    /**
     * @param requestPreamble request-only system messages inserted after the tool
     *                        policy and before the conversation (e.g. the
     *                        {@code 【用户记忆】} block); evaluated once per
     *                        {@code run}, never stored in the memory
     */
    public ToolLoop(LlmClient client, Supplier<List<AgentTool>> tools,
            Supplier<List<ChatMessage>> requestPreamble) {
        this(client, tools, requestPreamble, DEFAULT_MAX_ROUNDS);
    }

    public ToolLoop(LlmClient client, Supplier<List<AgentTool>> tools,
            Supplier<List<ChatMessage>> requestPreamble, int maxRounds) {
        this.client = Objects.requireNonNull(client, "client");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.requestPreamble = Objects.requireNonNull(requestPreamble, "requestPreamble");
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1 (got " + maxRounds + ")");
        }
        this.maxRounds = maxRounds;
    }

    private static Supplier<List<AgentTool>> fixed(List<AgentTool> tools) {
        Objects.requireNonNull(tools, "tools");
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.name());
            }
        }
        List<AgentTool> copy = List.copyOf(tools);
        return () -> copy;
    }

    /**
     * Runs one user turn without streaming. The reply text (everything the model
     * said across tool rounds) is returned.
     */
    public String run(ConversationMemory memory, String userContent) {
        return runTurn(memory, ChatMessage.user(userContent), null, false);
    }

    /**
     * Runs one user turn, streaming text deltas to {@code onDelta} as they arrive
     * (across tool rounds). The reply text (everything the model said across tool
     * rounds) is returned.
     */
    public String run(ConversationMemory memory, String userContent, Consumer<String> onDelta) {
        return runTurn(memory, ChatMessage.user(userContent), Objects.requireNonNull(onDelta, "onDelta"), true);
    }

    /**
     * Runs one user turn carrying images (URLs / {@code data:} URIs for vision
     * models) without streaming. {@code images} may be {@code null} or empty.
     */
    public String run(ConversationMemory memory, String userContent, List<String> images) {
        return runTurn(memory, ChatMessage.user(userContent, images), null, false);
    }

    /**
     * Runs one user turn carrying images (URLs / {@code data:} URIs for vision
     * models), streaming text deltas to {@code onDelta} as they arrive.
     * {@code images} may be {@code null} or empty.
     */
    public String run(ConversationMemory memory, String userContent, List<String> images, Consumer<String> onDelta) {
        return runTurn(memory, ChatMessage.user(userContent, images), Objects.requireNonNull(onDelta, "onDelta"), true);
    }

    private String runTurn(ConversationMemory memory, ChatMessage userTurn, Consumer<String> onDelta, boolean stream) {
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(userTurn, "userTurn");
        ToolSet toolSet = snapshot();

        List<ChatMessage> payload = new ArrayList<>(memory.context());
        int insertAt = 0;
        if (!toolSet.specs().isEmpty()) {
            // The policy leads the request whenever tools are on offer. It is
            // request-only — never stored, so history and folding are untouched.
            payload.add(0, ChatMessage.system(TOOL_POLICY));
            insertAt = 1;
        }
        List<ChatMessage> preamble = requestPreamble.get();
        if (preamble != null && !preamble.isEmpty()) {
            // Request-only extras (e.g. the memory block) follow the policy and
            // lead the conversation; like the policy they never enter memory.
            payload.addAll(insertAt, preamble);
        }
        payload.add(userTurn);
        List<ChatMessage> turns = new ArrayList<>();
        StringBuilder reply = new StringBuilder();
        StringBuilder streamed = new StringBuilder();

        try {
            for (int round = 0; round < maxRounds; round++) {
                streamed.setLength(0);
                long askStart = System.nanoTime();
                AssistantTurn turn = ask(payload, delta -> {
                    TurnTimings.recordFirstDelta();
                    streamed.append(delta);
                    if (onDelta != null) {
                        onDelta.accept(delta);
                    }
                }, stream, toolSet);
                TurnTimings.recordLlmRound((System.nanoTime() - askStart) / 1_000_000);
                TurnTimings.recordTokenUsage(turn.usage());
                reply.append(turn.content());

                if (!turn.hasToolCalls()) {
                    turns.add(ChatMessage.assistant(turn.content(), turn.usage()));
                    commit(memory, userTurn, turns);
                    return reply.toString();
                }

                ChatMessage assistant = ChatMessage.assistantToolCalls(turn.content(), turn.toolCalls(), turn.usage());
                turns.add(assistant);
                payload.add(assistant);
                for (ToolCall call : turn.toolCalls()) {
                    long toolStart = System.nanoTime();
                    String output = executeTool(toolSet, call);
                    TurnTimings.recordTool(call.name(), (System.nanoTime() - toolStart) / 1_000_000);
                    ChatMessage result = ChatMessage.tool(output, call.id());
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
                commit(memory, userTurn, turns);
            }
            throw ex;
        }
    }

    /** One run's tool view: name lookup plus the wire definitions. */
    private record ToolSet(Map<String, AgentTool> byName, List<ToolSpec> specs) {

        static final ToolSet EMPTY = new ToolSet(Map.of(), List.of());
    }

    private ToolSet snapshot() {
        List<AgentTool> supplied = tools.get();
        if (supplied == null || supplied.isEmpty()) {
            return ToolSet.EMPTY;
        }
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        List<ToolSpec> specs = new ArrayList<>();
        for (AgentTool tool : supplied) {
            if (tool == null) {
                continue;
            }
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                log.warn("duplicate tool name in dynamic tool list: {}; first entry wins", tool.name());
                continue;
            }
            specs.add(ToolSpec.of(tool));
        }
        return new ToolSet(Map.copyOf(byName), List.copyOf(specs));
    }

    /**
     * One model round-trip. With no tools on offer the request omits the
     * {@code tools} field entirely (identical to the plain text path) and any
     * tool calls the model hallucinates are dropped — only text and usage come
     * back, so a tool-less run can never surface a tool call.
     */
    private AssistantTurn ask(List<ChatMessage> payload, Consumer<String> onDelta, boolean stream, ToolSet toolSet) {
        AssistantTurn turn = stream
                ? client.completeStream(payload, toolSet.specs(), onDelta)
                : client.complete(payload, toolSet.specs());
        if (toolSet.specs().isEmpty()) {
            return new AssistantTurn(turn.content(), List.of(), turn.usage());
        }
        return turn;
    }

    /** Expected failures are returned as result text so the model can react; nothing escapes. */
    private String executeTool(ToolSet toolSet, ToolCall call) {
        AgentTool tool = toolSet.byName().get(call.name());
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

    private static void commit(ConversationMemory memory, ChatMessage userTurn, List<ChatMessage> turns) {
        List<ChatMessage> next = new ArrayList<>(memory.messages());
        next.add(userTurn);
        next.addAll(turns);
        memory.replaceAll(next);
    }
}
