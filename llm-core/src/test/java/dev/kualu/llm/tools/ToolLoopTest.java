package dev.kualu.llm.tools;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.AssistantTurn;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.ToolSpec;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.llm.metrics.TurnTimings;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopTest {

    /** Records calls and returns canned output. */
    static class RecordingTool implements AgentTool {
        final List<String> executions = new ArrayList<>();
        String output = "tool-output";

        @Override
        public String name() {
            return "exec";
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public String parametersJson() {
            return "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}";
        }

        @Override
        public String execute(String argumentsJson) {
            executions.add(argumentsJson);
            return output;
        }
    }

    /** Scripted model: each request consumes the next canned turn. */
    static class ScriptedLlm extends LlmClient {
        final Deque<AssistantTurn> script = new ArrayDeque<>();
        final List<List<ChatMessage>> requests = new ArrayList<>();

        ScriptedLlm() {
            super(new LlmConfig("http://localhost", "key", "model"));
        }

        void replyWith(String content, ToolCall... calls) {
            script.add(new AssistantTurn(content, List.of(calls)));
        }

        void failWith(RuntimeException failure) {
            failures.add(failure);
        }

        final Deque<RuntimeException> failures = new ArrayDeque<>();

        @Override
        public AssistantTurn complete(List<ChatMessage> messages, List<ToolSpec> tools) {
            return next(messages);
        }

        @Override
        public AssistantTurn completeStream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onDelta) {
            AssistantTurn turn = next(messages);
            if (!turn.content().isEmpty()) {
                onDelta.accept(turn.content());
            }
            return turn;
        }

        private AssistantTurn next(List<ChatMessage> messages) {
            requests.add(List.copyOf(messages));
            RuntimeException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
            AssistantTurn turn = script.poll();
            if (turn == null) {
                throw new IllegalStateException("script exhausted");
            }
            return turn;
        }
    }

    @Test
    void toolCallsAreExecutedAndFedBackToTheModel() {
        RecordingTool tool = new RecordingTool();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replyWith("我先看看。", new ToolCall("call_1", "exec", "{\"command\":\"ls\"}"));
        llm.replyWith("done");
        ConversationMemory memory = new ConversationMemory();

        String reply = new ToolLoop(llm, List.of(tool)).run(memory, "run ls");

        assertEquals("我先看看。done", reply);
        assertEquals(List.of("{\"command\":\"ls\"}"), tool.executions);

        List<ChatMessage> messages = memory.messages();
        assertEquals(4, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("run ls", messages.get(0).content());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("我先看看。", messages.get(1).content());
        assertEquals(1, messages.get(1).toolCalls().size());
        assertEquals("call_1", messages.get(1).toolCalls().get(0).id());
        assertEquals("tool", messages.get(2).role());
        assertEquals("call_1", messages.get(2).toolCallId());
        assertEquals("tool-output", messages.get(2).content());
        assertEquals("assistant", messages.get(3).role());
        assertEquals("done", messages.get(3).content());

        // Second request carried the tool round for the model to see.
        List<ChatMessage> secondRequest = llm.requests.get(1);
        assertEquals("assistant", secondRequest.get(1).role());
        assertEquals("tool", secondRequest.get(2).role());
    }

    @Test
    void turnTimingsRecordRoundsAndLlmTime() {
        RecordingTool tool = new RecordingTool();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replyWith("", new ToolCall("call_1", "exec", "{\"command\":\"ls\"}"));
        llm.replyWith("done");
        ConversationMemory memory = new ConversationMemory();

        TurnTimings timings = TurnTimings.begin();
        try {
            new ToolLoop(llm, List.of(tool)).run(memory, "run ls");
            assertEquals(2, timings.llmRounds());
            assertTrue(timings.llmMs() >= 0);
            assertEquals(-1, timings.llmTtftMs(), "non-streamed turns carry no ttft");
        } finally {
            TurnTimings.clear();
        }
    }

    @Test
    void multipleToolCallsInOneTurnAreAllExecutedInOrder() {
        RecordingTool tool = new RecordingTool();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replyWith("",
                new ToolCall("call_a", "exec", "{\"command\":\"one\"}"),
                new ToolCall("call_b", "exec", "{\"command\":\"two\"}"));
        llm.replyWith("ok");

        String reply = new ToolLoop(llm, List.of(tool)).run(new ConversationMemory(), "go");

        assertEquals("ok", reply);
        assertEquals(List.of("{\"command\":\"one\"}", "{\"command\":\"two\"}"), tool.executions);
    }

    @Test
    void streamingForwardsTextFromEveryRound() {
        RecordingTool tool = new RecordingTool();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replyWith("first", new ToolCall("call_1", "exec", "{}"));
        llm.replyWith("second");

        List<String> deltas = new ArrayList<>();
        String reply = new ToolLoop(llm, List.of(tool))
                .run(new ConversationMemory(), "go", deltas::add);

        assertEquals(List.of("first", "second"), deltas);
        assertEquals("firstsecond", reply);
    }

    @Test
    void unknownToolBecomesAnErrorResultAndTheLoopContinues() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.replyWith("", new ToolCall("call_1", "nope", "{}"));
        llm.replyWith("fine");

        ConversationMemory memory = new ConversationMemory();
        String reply = new ToolLoop(llm, List.of(new RecordingTool())).run(memory, "go");

        assertEquals("fine", reply);
        assertEquals("error: unknown tool 'nope'", memory.messages().get(2).content());
    }

    @Test
    void toolFailureIsReturnedAsResultText() {
        RecordingTool tool = new RecordingTool() {
            @Override
            public String execute(String argumentsJson) {
                throw new IllegalStateException("boom");
            }
        };
        ScriptedLlm llm = new ScriptedLlm();
        llm.replyWith("", new ToolCall("call_1", "exec", "{}"));
        llm.replyWith("fine");

        ConversationMemory memory = new ConversationMemory();
        new ToolLoop(llm, List.of(tool)).run(memory, "go");

        assertEquals("error: tool 'exec' failed: boom", memory.messages().get(2).content());
    }

    @Test
    void exceedingTheRoundLimitThrowsAndKeepsTheToolRounds() {
        RecordingTool tool = new RecordingTool();
        ScriptedLlm llm = new ScriptedLlm();
        llm.replyWith("", new ToolCall("call_1", "exec", "{}"));
        llm.replyWith("", new ToolCall("call_2", "exec", "{}"));
        ConversationMemory memory = new ConversationMemory();

        assertThrows(LlmException.class,
                () -> new ToolLoop(llm, List.of(tool), 2).run(memory, "go"));

        List<ChatMessage> messages = memory.messages();
        assertEquals(5, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("tool", messages.get(2).role());
        assertEquals("tool", messages.get(4).role());
    }

    @Test
    void aRunThatProducedNothingLeavesMemoryUntouched() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.failWith(new LlmException("upstream down"));
        ConversationMemory memory = new ConversationMemory();

        assertThrows(LlmException.class,
                () -> new ToolLoop(llm, List.of(new RecordingTool())).run(memory, "go"));

        assertEquals(0, memory.size());
    }

    @Test
    void partialStreamTextIsKeptWhenTheCallFailsMidStream() {
        ScriptedLlm llm = new ScriptedLlm() {
            @Override
            public AssistantTurn completeStream(List<ChatMessage> messages, List<ToolSpec> tools,
                                                Consumer<String> onDelta) {
                requests.add(List.copyOf(messages));
                onDelta.accept("partial");
                throw new LlmException("stream cut");
            }
        };
        ConversationMemory memory = new ConversationMemory();

        assertThrows(LlmException.class,
                () -> new ToolLoop(llm, List.of(new RecordingTool())).run(memory, "go", delta -> {}));

        List<ChatMessage> messages = memory.messages();
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("partial", messages.get(1).content());
    }

    @Test
    void withoutToolsThePlainTextPathIsUsedAndMemoryGetsTheTurn() {
        // No tools configured: the loop delegates to chat(), exactly like before.
        LlmClient llm = new LlmClient(new LlmConfig("http://localhost", "key", "model")) {
            @Override
            public String chat(List<ChatMessage> messages) {
                return "plain";
            }
        };
        ConversationMemory memory = new ConversationMemory();

        String reply = new ToolLoop(llm, List.of()).run(memory, "hi");

        assertEquals("plain", reply);
        assertEquals(2, memory.size());
        assertNull(memory.messages().get(0).toolCalls());
    }

    @Test
    void userTurnImagesAreSentToTheModelAndCommitted() {
        List<List<ChatMessage>> requests = new ArrayList<>();
        LlmClient llm = new LlmClient(new LlmConfig("http://localhost", "key", "model")) {
            @Override
            public String chat(List<ChatMessage> messages) {
                requests.add(List.copyOf(messages));
                return "一只猫";
            }
        };
        ConversationMemory memory = new ConversationMemory();
        List<String> images = List.of("data:image/png;base64,AAAA");

        String reply = new ToolLoop(llm, List.of()).run(memory, "这是什么？", images);

        assertEquals("一只猫", reply);
        ChatMessage sent = requests.get(0).get(requests.get(0).size() - 1);
        assertEquals("user", sent.role());
        assertEquals("这是什么？", sent.content());
        assertEquals(images, sent.images());
        ChatMessage committed = memory.messages().get(0);
        assertEquals("这是什么？", committed.content());
        assertEquals(images, committed.images());
    }

    @Test
    void duplicateToolNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolLoop(new ScriptedLlm(), List.of(new RecordingTool(), new RecordingTool())));
    }
}
