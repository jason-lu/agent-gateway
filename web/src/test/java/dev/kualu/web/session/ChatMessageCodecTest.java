package dev.kualu.web.session;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.memory.MemoryCompressor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageCodecTest {

    private final ChatMessageCodec codec = new ChatMessageCodec(new ObjectMapper());

    private ChatMessage roundTrip(ChatMessage message) {
        return codec.decode(codec.encode(message));
    }

    @Test
    void roundTrip_preservesEveryRole() {
        for (ChatMessage message : List.of(
                ChatMessage.system("you are terse"),
                ChatMessage.user("run ls"),
                ChatMessage.assistant("here you go"),
                ChatMessage.tool("file1\nfile2", "call_abc"),
                ChatMessage.user("thanks"))) {
            assertEquals(message, roundTrip(message));
        }
    }

    @Test
    void toolCallId_survivesTheRoundTrip() {
        assertEquals("call_42", roundTrip(ChatMessage.tool("output", "call_42")).toolCallId());
    }

    @Test
    void toolCalls_surviveTheRoundTrip() {
        ChatMessage message = ChatMessage.assistantToolCalls("let me look",
                List.of(new ToolCall("call_1", "exec", "{\"command\":\"ls\"}")));

        ChatMessage decoded = roundTrip(message);

        assertEquals(message, decoded);
        assertEquals("call_1", decoded.toolCalls().get(0).id());
        assertEquals("exec", decoded.toolCalls().get(0).name());
        assertEquals("{\"command\":\"ls\"}", decoded.toolCalls().get(0).arguments());
    }

    @Test
    void toolCalls_areOmittedFromRowWhenAbsent() {
        ChatMessageCodec.Encoded encoded = codec.encode(ChatMessage.user("hi"));

        assertNull(encoded.toolCalls(), "absent calls must not be written: " + encoded);
        assertNull(roundTrip(ChatMessage.user("hi")).toolCalls());
    }

    @Test
    void images_surviveTheRoundTrip() {
        List<String> images = List.of("data:image/png;base64,AAAA", "https://example.com/cat.png");
        ChatMessage message = ChatMessage.user("what is this?", images);

        ChatMessage decoded = roundTrip(message);

        assertEquals(message, decoded);
        assertEquals(images, decoded.images());
    }

    @Test
    void images_areOmittedFromRowWhenAbsent() {
        ChatMessageCodec.Encoded encoded = codec.encode(ChatMessage.user("hi"));

        assertNull(encoded.images(), "absent images must not be written: " + encoded);
        assertNull(roundTrip(ChatMessage.user("hi")).images());
    }

    @Test
    void nullToolCallId_isStoredAsNullNotEmpty() {
        ChatMessageCodec.Encoded encoded = codec.encode(ChatMessage.user("hi"));

        assertNull(encoded.toolCallId());
        assertNull(roundTrip(ChatMessage.user("hi")).toolCallId());
    }

    @Test
    void compressedSummary_survivesUnchanged() {
        String summaryBody = "The user is building a parser; next step is error recovery.";
        ChatMessage message = ChatMessage.system(MemoryCompressor.SUMMARY_PREFIX + summaryBody);

        ChatMessage decoded = roundTrip(message);

        assertEquals(message, decoded);
        assertTrue(decoded.content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
    }

    @Test
    void multilineAndUnicodeContent_areNotMangled() {
        String content = "line one\nline two\twith \"quotes\" and \\backslash\\";
        assertEquals(content, roundTrip(ChatMessage.user(content)).content());
    }

    @Test
    void corruptListJson_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeToolCalls("{not json"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeImages("{not json"));
    }

    @Test
    void emptyOrNullLists_decodeToNull() {
        assertNull(codec.decodeToolCalls(null));
        assertNull(codec.decodeToolCalls(""));
        assertNull(codec.decodeImages("[]"));
    }
}
