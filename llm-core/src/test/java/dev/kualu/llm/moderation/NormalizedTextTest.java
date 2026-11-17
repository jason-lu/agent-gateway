package dev.kualu.llm.moderation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalizedTextTest {

    @Test
    void fullWidthAndAsciiFoldTogether() {
        assertEquals("abc123", NormalizedText.of("ＡＢＣ１２３").text());
        assertEquals("abc123", NormalizedText.of("abc123").text());
    }

    @Test
    void traditionalMapsToSimplified() {
        assertEquals("法轮功", NormalizedText.of("法輪功").text());
        assertEquals("中国共产党党员", NormalizedText.of("中國共產黨黨員").text());
    }

    @Test
    void zeroWidthAndSeparatorsAreStripped() {
        assertEquals("我爱中国", NormalizedText.of("我​爱*中  国").text());
        assertEquals("法轮功", NormalizedText.of("法·轮×功").text());
    }

    @Test
    void confusablesFoldToLatin() {
        // Cyrillic а/е/о are folded so homoglyph evasion cannot dodge the lexicon.
        assertEquals("abc", NormalizedText.of("аbс").text());
    }

    @Test
    void lowerCasing() {
        assertEquals("hello", NormalizedText.of("HeLLo").text());
    }

    @Test
    void spanMappingCoversOriginalCharacters() {
        NormalizedText normalized = NormalizedText.of("我*爱*你");
        assertEquals("我爱你", normalized.text());
        assertEquals("我*爱*你", normalized.originalSubstring(0, 3));
    }

    @Test
    void spanMappingSkipsStrippedPrefix() {
        NormalizedText normalized = NormalizedText.of("  法*轮*功");
        assertEquals("法轮功", normalized.text());
        ModerationHit.Span span = normalized.toOriginalSpan(0, 3);
        assertEquals("法*轮*功", normalized.original().substring(span.start(), span.end()));
    }

    @Test
    void emptyAndAllStripped() {
        assertEquals("", NormalizedText.of("").text());
        assertEquals("", NormalizedText.of("  \t\n​  ").text());
    }
}
