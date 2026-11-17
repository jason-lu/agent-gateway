package dev.kualu.llm.moderation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentGateTest {

    private static final String LEXICON = """
            {
              "categories": {
                "VIOLENCE": {"severity": "HIGH", "terms": [{"pattern": "暴力示例词", "type": "exact"}]},
                "ABUSE": {"severity": "LOW", "terms": [{"pattern": "脏话示例", "type": "exact"}]}
              }
            }
            """;

    private static ContentModerator moderator() throws IOException {
        Path file = Files.createTempFile("lexicon", ".json");
        Files.writeString(file, LEXICON);
        LexiconModerator lexicon = new LexiconModerator(new ModerationPolicy(Mode.BLOCK), file.toString());
        return new ContentModerator(lexicon, null, new ModerationPolicy(Mode.BLOCK));
    }

    @Test
    void cleanStreamForwardsAtSentenceBoundaries() throws IOException {
        List<String> out = new ArrayList<>();
        SegmentGate gate = new SegmentGate(moderator(), out::add);
        gate.accept("第一句");
        assertTrue(out.isEmpty()); // held back until the boundary
        gate.accept("。第二句！");
        gate.accept("尾巴");
        gate.finish();
        assertEquals(List.of("第一句。", "第二句！"), out.subList(0, 2));
        assertEquals("第一句。第二句！尾巴", String.join("", out));
    }

    @Test
    void blockedSegmentAbortsAndIsNotForwarded() throws IOException {
        List<String> out = new ArrayList<>();
        SegmentGate gate = new SegmentGate(moderator(), out::add);
        gate.accept("安全的一句。");
        ModerationBlockedException ex = assertThrows(ModerationBlockedException.class,
                () -> gate.accept("这里有暴力示例词。后面还有一句。"));
        assertEquals(Direction.OUTPUT, ex.direction());
        assertEquals(Category.VIOLENCE, ex.hit().category());
        assertEquals(List.of("安全的一句。"), out);
    }

    @Test
    void redactedSegmentIsMaskedBeforeForwarding() throws IOException {
        List<String> out = new ArrayList<>();
        SegmentGate gate = new SegmentGate(moderator(), out::add);
        gate.accept("你说脏话示例吗。");
        gate.finish();
        assertEquals(List.of("你说***吗。"), out);
    }

    @Test
    void finishFlushesTail() throws IOException {
        List<String> out = new ArrayList<>();
        SegmentGate gate = new SegmentGate(moderator(), out::add);
        gate.accept("无句号结尾");
        gate.finish();
        assertEquals(List.of("无句号结尾"), out);
    }

    @Test
    void afterAbortDeltasAreDropped() throws IOException {
        List<String> out = new ArrayList<>();
        SegmentGate gate = new SegmentGate(moderator(), out::add);
        assertThrows(ModerationBlockedException.class, () -> gate.accept("暴力示例词。"));
        gate.accept("更多文本。");
        gate.finish();
        assertTrue(out.isEmpty());
    }
}
