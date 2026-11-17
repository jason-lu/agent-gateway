package dev.kualu.llm.moderation;

/**
 * Thrown when moderation blocks a text: user input refused at the gate, or a
 * streamed reply cut mid-segment. Carries the finding for audit and for the
 * SSE {@code moderation} event.
 */
public class ModerationBlockedException extends RuntimeException {

    private final ModerationHit hit;
    private final Direction direction;

    public ModerationBlockedException(ModerationHit hit, Direction direction) {
        super(ModerationMessages.BLOCK_MESSAGE);
        this.hit = hit;
        this.direction = direction;
    }

    public ModerationHit hit() {
        return hit;
    }

    public Direction direction() {
        return direction;
    }
}
