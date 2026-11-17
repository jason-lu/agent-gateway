package dev.kualu.llm.moderation;

/** What to do with a confirmed finding. */
public enum Action {
    /** Refuse the turn outright (or cut a stream). */
    BLOCK,
    /** Mask the matched spans and continue. */
    REDACT,
    /** Let it through but record it for review (gray-release / borderline). */
    FLAG
}
