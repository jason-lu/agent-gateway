package dev.kualu.llm.moderation;

import java.util.Optional;

/**
 * One detection stage. Implementations: {@link LexiconModerator} (synchronous
 * rules) and {@link LlmModerator} (semantic classification).
 */
public interface TextModerator {

    /**
     * Checks one text and returns the finding, or empty when nothing matched.
     * Never throws for a moderation miss — infrastructure failures degrade to
     * "no finding" at the {@link LlmModerator} level.
     *
     * @param text      the text to check (original, not normalized)
     * @param direction which side of the conversation it came from
     */
    Optional<ModerationHit> check(String text, Direction direction);
}
