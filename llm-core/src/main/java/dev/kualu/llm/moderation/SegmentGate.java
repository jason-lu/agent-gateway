package dev.kualu.llm.moderation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Streams model deltas through the moderation fast path (L1 per sentence
 * segment) before they reach the client.
 *
 * <p>Deltas accumulate until a sentence boundary (。！？；…!?.; or newline); the
 * completed segment is checked and only then forwarded — masked when the finding
 * is REDACT, never at all when it is BLOCK ({@link ModerationBlockedException}
 * aborts the run). An un-terminated tail is held at most {@link #DEFAULT_MAX_HOLD}
 * (then checked and forwarded whole) and otherwise waits for {@link #finish()}.
 * What the segment gate passes can still be caught by the full pre-persist check
 * (L2 semantics, cross-segment terms) — that later hit replaces the stored reply.
 */
public final class SegmentGate implements Consumer<String> {

    private static final String BOUNDARIES = "。！？；…!?.;\n";

    /** How long a tail may wait for a boundary before it is forwarded anyway. */
    public static final Duration DEFAULT_MAX_HOLD = Duration.ofMillis(150);

    private final ContentModerator moderator;
    private final Consumer<String> sink;
    private final Consumer<ModerationHit> onFinding;
    private final long maxHoldNanos;
    private final StringBuilder buffer = new StringBuilder();
    private long lastForwardNanos = System.nanoTime();
    private boolean aborted;

    public SegmentGate(ContentModerator moderator, Consumer<String> sink) {
        this(moderator, sink, hit -> {
        });
    }

    /**
     * @param onFinding notified of every finding (REDACT and FLAG before the
     *                  segment is forwarded, BLOCK before the abort) — the audit hook
     */
    public SegmentGate(ContentModerator moderator, Consumer<String> sink, Consumer<ModerationHit> onFinding) {
        this(moderator, sink, onFinding, DEFAULT_MAX_HOLD);
    }

    /** Package-private for tests: {@code maxHold} bounds how long a tail waits for a boundary. */
    SegmentGate(ContentModerator moderator, Consumer<String> sink, Consumer<ModerationHit> onFinding,
            Duration maxHold) {
        this.moderator = Objects.requireNonNull(moderator, "moderator");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.onFinding = Objects.requireNonNull(onFinding, "onFinding");
        this.maxHoldNanos = Objects.requireNonNull(maxHold, "maxHold").toNanos();
    }

    @Override
    public void accept(String delta) {
        if (aborted || delta == null || delta.isEmpty()) {
            return;
        }
        buffer.append(delta);
        pump(false);
        // Forced flush: streaming must not stall waiting for a sentence end. The
        // check runs on arrival, so the real hold is maxHold plus the upstream
        // inter-delta gap. checkSegment is lexicon-only (L1), so an extra flush
        // costs nothing; terms split across a forced cut fall through to L2.
        if (!buffer.isEmpty() && System.nanoTime() - lastForwardNanos >= maxHoldNanos) {
            pump(true);
        }
    }

    /** Flushes the unterminated tail at end of stream (same checks as completed segments). */
    public void finish() {
        if (aborted) {
            return;
        }
        pump(true);
    }

    private void pump(boolean flushAll) {
        int cut;
        while ((cut = nextBoundary(buffer, flushAll)) > 0) {
            String segment = buffer.substring(0, cut);
            buffer.delete(0, cut);
            if (segment.isBlank()) {
                continue;
            }
            Optional<ModerationHit> hit = moderator.checkSegment(segment);
            if (hit.isPresent()) {
                onFinding.accept(hit.get());
                if (hit.get().action() == Action.BLOCK) {
                    aborted = true;
                    throw new ModerationBlockedException(hit.get(), Direction.OUTPUT);
                }
            }
            String out = hit.isPresent() && hit.get().action() == Action.REDACT
                    ? moderator.redact(segment, hit.get())
                    : segment;
            sink.accept(out);
            lastForwardNanos = System.nanoTime();
        }
    }

    /** Cut after the first boundary character; no boundary yet → -1 (or the whole buffer at finish). */
    private static int nextBoundary(CharSequence buffer, boolean flushAll) {
        for (int i = 0; i < buffer.length(); i++) {
            if (BOUNDARIES.indexOf(buffer.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return flushAll ? buffer.length() : -1;
    }
}
