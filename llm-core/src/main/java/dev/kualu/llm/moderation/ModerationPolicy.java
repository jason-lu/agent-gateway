package dev.kualu.llm.moderation;

import java.util.Objects;

/**
 * The disposition matrix: severity × mode → action, and which findings need
 * human review. One policy instance per deployment (from {@code agent.moderation.mode}).
 */
public record ModerationPolicy(Mode mode) {

    public ModerationPolicy {
        Objects.requireNonNull(mode, "mode");
    }

    /**
     * Action for a finding. In {@link Mode#FLAG} (gray release) every finding is
     * recorded and nothing is intercepted.
     */
    public Action actionFor(Severity severity) {
        if (mode == Mode.FLAG) {
            return Action.FLAG;
        }
        return switch (severity) {
            case HIGH, MEDIUM -> Action.BLOCK;
            case LOW -> Action.REDACT;
        };
    }

    /** MEDIUM findings go to the review queue whatever the mode; the others are audit-only. */
    public boolean needsReview(Severity severity) {
        return severity == Severity.MEDIUM;
    }

    /**
     * Whether a hit deserves human eyes: the MEDIUM rule above, plus every
     * {@link Action#FLAG} finding (gray-release data collection, and the LLM
     * classifier's unsure verdicts).
     */
    public boolean needsReview(ModerationHit hit) {
        return needsReview(hit.severity()) || hit.action() == Action.FLAG;
    }
}
