package dev.kualu.llm.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexiconModeratorTest {

    private static final String LEXICON = """
            {
              "version": 1,
              "categories": {
                "VIOLENCE": {"severity": "HIGH", "terms": [
                  {"pattern": "暴力示例词", "type": "exact"},
                  {"pattern": "暴*力*示*例*词", "type": "wildcard"}
                ]},
                "ABUSE": {"severity": "LOW", "terms": [
                  {"pattern": "脏话示例", "type": "exact"}
                ]},
                "FRAUD": {"severity": "MEDIUM", "terms": [
                  {"pattern": "诈[骗欺]示例", "type": "regex"}
                ]}
              }
            }
            """;

    private static LexiconModerator moderator(String json) throws IOException {
        return moderator(json, new ModerationPolicy(Mode.BLOCK));
    }

    private static LexiconModerator moderator(String json, ModerationPolicy policy) throws IOException {
        Path file = Files.createTempFile("lexicon", ".json");
        Files.writeString(file, json);
        return new LexiconModerator(policy, file.toString());
    }

    @Test
    void exactMatchFindsHighestSeverity() throws IOException {
        LexiconModerator moderator = moderator(LEXICON);
        Optional<ModerationHit> hit = moderator.check("前面脏话示例后面暴力示例词", Direction.INPUT);
        assertTrue(hit.isPresent());
        assertEquals(Category.VIOLENCE, hit.get().category());
        assertEquals(Severity.HIGH, hit.get().severity());
        assertEquals(Action.BLOCK, hit.get().action());
    }

    @Test
    void exactMatchSurvivesSeparatorAndTraditionalEvasion() throws IOException {
        LexiconModerator moderator = moderator(LEXICON);
        assertTrue(moderator.check("暴*力*示*例*词", Direction.INPUT).isPresent());
        assertTrue(moderator.check("暴 力 示 例 词", Direction.INPUT).isPresent());
    }

    @Test
    void wildcardMatchesCjkFillers() throws IOException {
        LexiconModerator moderator = moderator(LEXICON);
        // Separators are stripped in the detection view, so the exact term catches
        // those; wildcard covers real character fillers that survive normalization.
        assertTrue(moderator.check("暴力的示的例的词", Direction.INPUT).isPresent());
    }

    @Test
    void regexTermMatches() throws IOException {
        LexiconModerator moderator = moderator(LEXICON);
        Optional<ModerationHit> hit = moderator.check("这是诈骗示例文本", Direction.INPUT);
        assertTrue(hit.isPresent());
        assertEquals(Category.FRAUD, hit.get().category());
        assertEquals(Severity.MEDIUM, hit.get().severity());
    }

    @Test
    void cleanTextPasses() throws IOException {
        LexiconModerator moderator = moderator(LEXICON);
        assertTrue(moderator.check("今天天气真好，一起去散步吧", Direction.INPUT).isEmpty());
    }

    @Test
    void lowSeverityRedacts() throws IOException {
        LexiconModerator moderator = moderator(LEXICON);
        Optional<ModerationHit> hit = moderator.check("你说脏话示例吗", Direction.INPUT);
        assertTrue(hit.isPresent());
        assertEquals(Action.REDACT, hit.get().action());
        String redacted = hit.get().redact("你说脏话示例吗");
        assertEquals("你说***吗", redacted);
    }

    @Test
    void flagModeFlagsEverything() throws IOException {
        LexiconModerator moderator = moderator(LEXICON, new ModerationPolicy(Mode.FLAG));
        Optional<ModerationHit> hit = moderator.check("暴力示例词", Direction.INPUT);
        assertTrue(hit.isPresent());
        assertEquals(Action.FLAG, hit.get().action());
    }

    @Test
    void spansMapBackToOriginal() throws IOException {
        LexiconModerator moderator = moderator(LEXICON);
        ModerationHit hit = moderator.check("前缀暴*力*示*例*词后缀", Direction.INPUT).orElseThrow();
        assertEquals(1, hit.spans().size());
        ModerationHit.Span span = hit.spans().get(0);
        assertEquals("暴*力*示*例*词", "前缀暴*力*示*例*词后缀".substring(span.start(), span.end()));
        assertEquals("暴***词", hit.excerpt());
    }

    @Test
    void hotReloadPicksUpNewTerms(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("lexicon.json");
        Files.writeString(file, LEXICON);
        LexiconModerator moderator = new LexiconModerator(new ModerationPolicy(Mode.BLOCK), file.toString());
        assertTrue(moderator.check("热词示例甲", Direction.INPUT).isEmpty());

        Files.writeString(file, LEXICON.replace(
                "{\"pattern\": \"脏话示例\", \"type\": \"exact\"}",
                "{\"pattern\": \"脏话示例\", \"type\": \"exact\"}, {\"pattern\": \"热词示例甲\", \"type\": \"exact\"}"));
        assertTrue(moderator.check("热词示例甲", Direction.INPUT).isPresent());
    }

    @Test
    void brokenLexiconFailsFast() {
        assertThrows(RuntimeException.class, () -> new LexiconModerator(
                new ModerationPolicy(Mode.BLOCK), "classpath:moderation/does-not-exist.json"));
        assertThrows(RuntimeException.class, () -> moderator("not json"));
    }

    @Test
    void multipleSpansAllRedact() throws IOException {
        LexiconModerator moderator = moderator(LEXICON);
        ModerationHit hit = moderator.check("脏话示例和脏话示例", Direction.INPUT).orElseThrow();
        assertEquals(2, hit.spans().size());
        assertEquals("***和***", hit.redact("脏话示例和脏话示例"));
        List<ModerationHit.Span> spans = moderator.matchSpans("脏话示例和脏话示例");
        assertEquals(2, spans.size());
    }

    @Test
    void unknownCategoryRejected() {
        assertThrows(RuntimeException.class, () -> moderator("""
                {"categories": {"NOT_A_CATEGORY": {"severity": "HIGH", "terms": []}}}
                """));
    }

    @Test
    void emptyTermsCompile() throws IOException {
        LexiconModerator moderator = moderator("""
                {"categories": {"ABUSE": {"severity": "LOW", "terms": []}}}
                """);
        assertFalse(moderator.check("任意文本", Direction.INPUT).isPresent());
    }
}
