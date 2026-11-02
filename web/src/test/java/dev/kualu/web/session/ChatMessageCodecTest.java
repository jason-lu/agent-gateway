package dev.kualu.web.session;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.MemoryCompressor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageCodecTest {

    private final ChatMessageCodec codec = new ChatMessageCodec(new ObjectMapper());

    @Test
    void roundTrip_preservesOrderAndEveryRole() {
        List<ChatMessage> original = List.of(
                ChatMessage.system("you are terse"),
                ChatMessage.user("run ls"),
                ChatMessage.assistant("here you go"),
                ChatMessage.tool("file1\nfile2", "call_abc"),
                ChatMessage.user("thanks"));

        assertEquals(original, codec.decode(codec.encode(original)));
    }

    @Test
    void toolCallId_survivesTheRoundTrip() {
        List<ChatMessage> original = List.of(ChatMessage.tool("output", "call_42"));

        List<ChatMessage> decoded = codec.decode(codec.encode(original));

        assertEquals("call_42", decoded.get(0).toolCallId());
    }

    @Test
    void nullToolCallId_isOmittedFromJsonButNotConfusedWithEmpty() {
        String json = codec.encode(List.of(ChatMessage.user("hi")));

        assertFalse(json.contains("toolCallId"), "absent id must not be written: " + json);
        assertEquals("hi", codec.decode(json).get(0).content());
    }

    @Test
    void compressedSummary_survivesUnchanged() {
        String summaryBody = "The user is building a parser; next step is error recovery.";
        List<ChatMessage> original = List.of(
                ChatMessage.system("sys"),
                ChatMessage.system(MemoryCompressor.SUMMARY_PREFIX + summaryBody),
                ChatMessage.user("continue"),
                ChatMessage.assistant("sure"));

        List<ChatMessage> decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded);
        assertTrue(decoded.get(1).content().startsWith(MemoryCompressor.SUMMARY_PREFIX));
    }

    @Test
    void multilineAndUnicodeContent_areNotMangled() {
        String content = "第一行\n第二行\twith \"quotes\" and \\backslash\\";
        List<ChatMessage> original = List.of(ChatMessage.user(content));

        assertEquals(content, codec.decode(codec.encode(original)).get(0).content());
    }

    @Test
    void corruptHistory_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{not an array"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"role\":\"user\"}"));
    }
}
