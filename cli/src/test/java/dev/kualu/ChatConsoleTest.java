package dev.kualu;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.LlmClient;
import dev.kualu.llm.LlmConfig;
import dev.kualu.llm.LlmException;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConsoleTest {

    private static class FakeLlmClient extends LlmClient {
        final List<List<ChatMessage>> chatCalls = new ArrayList<>();
        final List<List<ChatMessage>> streamCalls = new ArrayList<>();

        FakeLlmClient() {
            super(new LlmConfig("http://test.local/v1", "key", "test-model"));
        }

        @Override
        public String chat(List<ChatMessage> messages) {
            chatCalls.add(messages);
            String last = messages.get(messages.size() - 1).content();
            return "full-reply:" + last;
        }

        @Override
        public String chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
            streamCalls.add(messages);
            onDelta.accept("Hel");
            onDelta.accept("lo");
            return "Hello";
        }
    }

    private static String run(FakeLlmClient client, String input, boolean streaming) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        new ChatConsole(client, in, out, streaming).run();
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static String lastUser(List<ChatMessage> messages) {
        return messages.get(messages.size() - 1).content();
    }

    @Test
    void defaultMode_streamsAndPrintsDeltas() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "hi\n/quit\n", true);

        assertEquals(1, client.streamCalls.size());
        assertEquals("hi", lastUser(client.streamCalls.get(0)));
        assertTrue(client.chatCalls.isEmpty());
        assertTrue(output.contains("assistant: Hello"));
        assertTrue(output.contains("you (stream)>"));
    }

    @Test
    void noStreamMode_usesFullChat() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "hi\n/quit\n", false);

        assertEquals(1, client.chatCalls.size());
        assertEquals("hi", lastUser(client.chatCalls.get(0)));
        assertTrue(client.streamCalls.isEmpty());
        assertTrue(output.contains("assistant: full-reply:hi"));
        assertTrue(output.contains("you (full)>"));
    }

    @Test
    void streamCommand_togglesMode() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "one\n/stream\ntwo\n/stream\nthree\n/quit\n", true);

        assertEquals(2, client.streamCalls.size());
        assertEquals(1, client.chatCalls.size());
        assertEquals("one", lastUser(client.streamCalls.get(0)));
        assertEquals("two", lastUser(client.chatCalls.get(0)));
        assertEquals("three", lastUser(client.streamCalls.get(1)));
        assertTrue(output.contains("Mode is now: full"));
        assertTrue(output.contains("Mode is now: stream"));
    }

    @Test
    void helpAndStatus_areAvailable() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "/help\n/status\n/quit\n", true);

        assertTrue(output.contains("Toggle stream vs full replies"));
        assertTrue(output.contains("Clear conversation memory"));
        assertTrue(output.contains("Show remembered conversation turns"));
        assertTrue(output.contains("test-model"));
        assertTrue(output.contains("http://test.local/v1"));
    }

    @Test
    void llmError_isPrintedAndLoopContinues() {
        FakeLlmClient client = new FakeLlmClient() {
            @Override
            public String chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
                streamCalls.add(messages);
                throw new LlmException("LLM request failed with HTTP 429 (rate limited)");
            }
        };

        String output = run(client, "boom\n/quit\n", true);

        assertTrue(output.contains("error: LLM request failed with HTTP 429"));
        assertTrue(output.contains("Bye."));
    }

    @Test
    void unknownSlashToken_isSentAsChat() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "/nope\n/quit\n", true);

        assertEquals(1, client.streamCalls.size());
        assertEquals("/nope", lastUser(client.streamCalls.get(0)));
        assertFalse(output.contains("Unknown command"));
    }

    @Test
    void absolutePath_isSentAsChatNotCommand() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "/usr/lib/jvm/jdk-21.0.5+11/bin/java\n/quit\n", true);

        assertEquals(1, client.streamCalls.size());
        assertEquals("/usr/lib/jvm/jdk-21.0.5+11/bin/java", lastUser(client.streamCalls.get(0)));
        assertFalse(output.contains("Unknown command"));
    }

    @Test
    void slashContainingContent_isSentAsChat() {
        FakeLlmClient client = new FakeLlmClient();

        run(client, "and/or\n1/2\nTCP/IP\nn/a\n/compare 3/4 vs 5/6\n/quit\n", false);

        assertEquals(5, client.chatCalls.size());
        assertEquals("and/or", lastUser(client.chatCalls.get(0)));
        assertEquals("1/2", lastUser(client.chatCalls.get(1)));
        assertEquals("TCP/IP", lastUser(client.chatCalls.get(2)));
        assertEquals("n/a", lastUser(client.chatCalls.get(3)));
        assertEquals("/compare 3/4 vs 5/6", lastUser(client.chatCalls.get(4)));
    }

    @Test
    void windowsPath_isSentAsChat() {
        FakeLlmClient client = new FakeLlmClient();

        run(client, "C:\\Program Files\\Java\\bin\\java\n/quit\n", false);

        assertEquals(1, client.chatCalls.size());
        assertEquals("C:\\Program Files\\Java\\bin\\java", lastUser(client.chatCalls.get(0)));
    }

    @Test
    void relativePath_isSentAsChat() {
        FakeLlmClient client = new FakeLlmClient();

        run(client, "./bin/java\n../lib/foo.jar\n/quit\n", false);

        assertEquals(2, client.chatCalls.size());
        assertEquals("./bin/java", lastUser(client.chatCalls.get(0)));
        assertEquals("../lib/foo.jar", lastUser(client.chatCalls.get(1)));
    }

    @Test
    void knownCommand_withExtraWords_stillRunsCommand() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "/help please\n/quit\n", true);

        assertTrue(output.contains("Commands:"));
        assertTrue(client.streamCalls.isEmpty());
    }

    @Test
    void memory_accumulatesTurnsAcrossMessages() {
        FakeLlmClient client = new FakeLlmClient();

        run(client, "one\ntwo\n/quit\n", false);

        assertEquals(2, client.chatCalls.size());

        List<ChatMessage> first = client.chatCalls.get(0);
        assertEquals(1, first.size());
        assertEquals("user", first.get(0).role());
        assertEquals("one", first.get(0).content());

        List<ChatMessage> second = client.chatCalls.get(1);
        assertEquals(3, second.size());
        assertEquals("user", second.get(0).role());
        assertEquals("one", second.get(0).content());
        assertEquals("assistant", second.get(1).role());
        assertEquals("full-reply:one", second.get(1).content());
        assertEquals("user", second.get(2).role());
        assertEquals("two", second.get(2).content());
    }

    @Test
    void clear_resetsMemory() {
        FakeLlmClient client = new FakeLlmClient();

        run(client, "one\n/clear\ntwo\n/quit\n", false);

        assertEquals(2, client.chatCalls.size());
        assertEquals(1, client.chatCalls.get(0).size());
        assertEquals(1, client.chatCalls.get(1).size());
        assertEquals("two", lastUser(client.chatCalls.get(1)));
    }

    @Test
    void history_showsRememberedTurns() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "hello there\n/history\n/quit\n", true);

        assertTrue(output.contains("History (2 turn(s))"));
        assertTrue(output.contains("[user] hello there"));
        assertTrue(output.contains("[assistant] Hello"));
    }

    @Test
    void historyEmpty_whenNoMessages() {
        FakeLlmClient client = new FakeLlmClient();

        String output = run(client, "/history\n/quit\n", true);

        assertTrue(output.contains("Memory is empty."));
    }

    @Test
    void failedLlmCall_doesNotAddToMemory() {
        FakeLlmClient client = new FakeLlmClient() {
            boolean first = true;

            @Override
            public String chat(List<ChatMessage> messages) {
                chatCalls.add(messages);
                if (first) {
                    first = false;
                    throw new LlmException("boom");
                }
                return "ok:" + messages.size();
            }
        };

        run(client, "fail\nretry\n/quit\n", false);

        assertEquals(2, client.chatCalls.size());
        // first call: only the user message
        assertEquals(1, client.chatCalls.get(0).size());
        // second call: still only the retry user message — failed turn was not stored
        assertEquals(1, client.chatCalls.get(1).size());
        assertEquals("retry", lastUser(client.chatCalls.get(1)));
    }
}
