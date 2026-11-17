package dev.kualu.llm.moderation;

import java.util.Objects;

/**
 * The LLM classifier's answer for one text.
 *
 * @param category   the violation category, or {@link Category#NONE} when clean
 * @param severity   seriousness (meaningful only when category != NONE)
 * @param confidence 0.0–1.0; below the threshold the verdict is "unsure"
 * @param reason     short justification (audit only)
 */
public record ModerationVerdict(Category category, Severity severity, double confidence, String reason) {

    public ModerationVerdict {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        if (reason == null) {
            reason = "";
        }
    }

    /** A clean result. */
    public static ModerationVerdict none() {
        return new ModerationVerdict(Category.NONE, Severity.LOW, 1.0, "");
    }

    /** True when the classifier found a violation at or above the confidence threshold. */
    public boolean isViolation(double confidenceThreshold) {
        return category != Category.NONE && confidence >= confidenceThreshold;
    }

    /** True when the classifier leaned toward a violation but was not sure enough to act. */
    public boolean isUnsure(double confidenceThreshold) {
        return category != Category.NONE && confidence < confidenceThreshold;
    }
}
