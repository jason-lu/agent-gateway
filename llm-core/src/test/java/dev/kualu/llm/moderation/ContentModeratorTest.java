package dev.kualu.llm.moderation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentModeratorTest {

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
    void lexiconHitShortCircuitsWithoutClassifier() throws IOException {
        ContentModerator moderator = moderator();
        Optional<ModerationHit> hit = moderator.checkInput("这里有暴力示例词");
        assertTrue(hit.isPresent());
        assertEquals(Category.VIOLENCE, hit.get().category());
    }

    @Test
    void cleanInputAndOutputPass() throws IOException {
        ContentModerator moderator = moderator();
        assertTrue(moderator.checkInput("正常对话内容").isEmpty());
        assertTrue(moderator.checkOutput("正常回复内容").isEmpty());
    }

    @Test
    void segmentCheckIsLexiconOnly() throws IOException {
        ContentModerator moderator = moderator();
        assertTrue(moderator.checkSegment("暴力示例词").isPresent());
        assertTrue(moderator.checkSegment("正常内容").isEmpty());
    }

    @Test
    void redactMasksLexiconSpans() throws IOException {
        ContentModerator moderator = moderator();
        ModerationHit hit = moderator.checkInput("开头脏话示例结尾").orElseThrow();
        assertEquals(Action.REDACT, hit.action());
        assertEquals("开头***结尾", moderator.redact("开头脏话示例结尾", hit));
    }

    @Test
    void redactWithoutSpansReturnsTextUnchanged() throws IOException {
        ContentModerator moderator = moderator();
        ModerationHit semantic = new ModerationHit(Category.ABUSE, Severity.LOW, Action.FLAG, "x", java.util.List.of());
        assertEquals("原样文本", moderator.redact("原样文本", semantic));
    }
}
