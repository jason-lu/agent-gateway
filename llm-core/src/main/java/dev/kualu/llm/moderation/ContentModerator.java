package dev.kualu.llm.moderation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The moderation pipeline facade: L1 lexicon first, L2 classifier only for what
 * the lexicon passed. Both sides of the conversation use the same pipeline; the
 * streaming fast path skips L2 per segment (it runs on the full reply instead).
 */
public final class ContentModerator {

    private final LexiconModerator lexicon;
    private final LlmModerator classifier; // nullable: L2 disabled
    private final ModerationPolicy policy;

    public ContentModerator(LexiconModerator lexicon, LlmModerator classifier, ModerationPolicy policy) {
        this.lexicon = Objects.requireNonNull(lexicon, "lexicon");
        this.classifier = classifier;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Full check (L1 + L2) for user input. */
    public Optional<ModerationHit> checkInput(String text) {
        return check(text, Direction.INPUT, true);
    }

    /** Full check (L1 + L2) for a complete model reply — the pre-persist gate. */
    public Optional<ModerationHit> checkOutput(String text) {
        return check(text, Direction.OUTPUT, true);
    }

    /**
     * Fast check (L1 only) for one streamed segment. A hit cuts the stream; what
     * L1 misses is caught by {@link #checkOutput} before the reply is persisted.
     */
    public Optional<ModerationHit> checkSegment(String text) {
        return check(text, Direction.OUTPUT, false);
    }

    private Optional<ModerationHit> check(String text, Direction direction, boolean useClassifier) {
        Objects.requireNonNull(text, "text");
        Optional<ModerationHit> ruleHit = lexicon.check(text, direction);
        if (ruleHit.isPresent()) {
            return ruleHit;
        }
        if (!useClassifier || classifier == null) {
            return Optional.empty();
        }
        return classifier.check(text, direction);
    }

    /**
     * Masks every lexicon span in {@code text}. For semantic findings without
     * spans the text is returned unchanged — those are FLAGed, never REDACTed
     * ({@link LlmModerator} already degrades REDACT to FLAG).
     */
    public String redact(String text, ModerationHit hit) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(hit, "hit");
        if (hit.hasSpans()) {
            return hit.redact(text);
        }
        List<ModerationHit.Span> spans = lexicon.matchSpans(text);
        if (spans.isEmpty()) {
            return text;
        }
        return new ModerationHit(hit.category(), hit.severity(), hit.action(), hit.excerpt(), spans).redact(text);
    }

    public ModerationPolicy policy() {
        return policy;
    }
}
