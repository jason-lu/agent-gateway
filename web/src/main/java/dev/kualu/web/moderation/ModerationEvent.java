package dev.kualu.web.moderation;

import dev.kualu.llm.moderation.Action;
import dev.kualu.llm.moderation.Category;
import dev.kualu.llm.moderation.Direction;
import dev.kualu.llm.moderation.Severity;

import java.time.Instant;

/**
 * One audit row: what was found, what was done, and the review verdict.
 * Privacy: {@code excerpt} is a masked fragment of the hit, {@code textSha256}
 * identifies the normalized text for appeals — the raw violation is never stored.
 */
public record ModerationEvent(
        long id,
        Instant createdAt,
        String owner,
        String sessionId,
        Direction direction,
        Category category,
        Severity severity,
        Action action,
        String detector,
        String excerpt,
        String textSha256,
        boolean needsReview,
        boolean reviewed,
        String reviewNote,
        String reviewOverride) {

    /** What {@link ModerationEventStore#record} takes: everything but the generated id. */
    public record New(
            String owner,
            String sessionId,
            Direction direction,
            Category category,
            Severity severity,
            Action action,
            String detector,
            String excerpt,
            String textSha256,
            boolean needsReview) {
    }
}
