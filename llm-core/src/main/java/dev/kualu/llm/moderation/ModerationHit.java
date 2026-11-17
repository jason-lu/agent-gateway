package dev.kualu.llm.moderation;

import java.util.List;
import java.util.Objects;

/**
 * One confirmed finding in a text.
 *
 * @param category  which violation category (never {@link Category#NONE})
 * @param severity  how serious it is
 * @param action    what to do with the text (from {@link ModerationPolicy})
 * @param excerpt   the matched span with its middle masked (e.g. {@code 法***功});
 *                  what the audit row stores — never the full raw text
 * @param spans     matched spans in original-text coordinates (empty for a
 *                  semantic L2 finding that has no located span)
 */
public record ModerationHit(
        Category category,
        Severity severity,
        Action action,
        String excerpt,
        List<Span> spans) {

    /** Half-open character range in the original text. */
    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("bad span [" + start + "," + end + ")");
            }
        }

        public int length() {
            return end - start;
        }
    }

    public ModerationHit {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(excerpt, "excerpt");
        spans = List.copyOf(Objects.requireNonNull(spans, "spans"));
    }

    /** True when at least one span is available for masking. */
    public boolean hasSpans() {
        return !spans.isEmpty();
    }

    /**
     * Replaces every matched span with {@code ***}. Spans are masked from the end
     * so earlier offsets stay valid. A hit without spans returns {@code text}
     * unchanged (semantic findings carry nothing to mask — callers should treat
     * {@link Action#REDACT} without spans as {@link Action#FLAG}).
     */
    public String redact(String text) {
        Objects.requireNonNull(text, "text");
        String result = text;
        List<Span> ordered = spans.stream()
                .sorted((a, b) -> Integer.compare(b.start(), a.start()))
                .toList();
        for (Span span : ordered) {
            int end = Math.min(span.end(), result.length());
            int start = Math.min(span.start(), end);
            result = result.substring(0, start) + "***" + result.substring(end);
        }
        return result;
    }

    /** Builds the audit excerpt from a span: first char + {@code ***} + last char (when long enough). */
    public static String excerptOf(String text, Span span) {
        int end = Math.min(span.end(), text.length());
        int start = Math.min(span.start(), end);
        String hit = text.substring(start, end);
        if (hit.length() <= 2) {
            return hit;
        }
        return hit.charAt(0) + "***" + hit.charAt(hit.length() - 1);
    }
}
