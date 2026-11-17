package dev.kualu.web.moderation;

import java.util.List;
import java.util.Optional;

/** Audit persistence for moderation findings. */
public interface ModerationEventStore {

    /** Inserts one audit row; returns the stored event with its generated id. */
    ModerationEvent record(ModerationEvent.New event);

    /** Newest-first page of events. */
    List<ModerationEvent> list(ModerationQuery query);

    Optional<ModerationEvent> find(long id);

    /**
     * Marks the event reviewed. {@code override} is the reviewer's verdict:
     * {@code CONFIRM} (a true positive — feeds the lexicon) or {@code NONE}
     * (a false kill — feeds the miss list); {@code null} leaves it unset.
     *
     * @return false when the id does not exist
     */
    boolean review(long id, String note, String override);

    /** Filters for {@link #list}. */
    record ModerationQuery(boolean unreviewedOnly, boolean needsReviewOnly, String override, int limit, int offset) {

        public ModerationQuery {
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("limit must be within 1..500");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0");
            }
        }

        public static ModerationQuery pending(int limit, int offset) {
            return new ModerationQuery(true, false, null, limit, offset);
        }

        public static ModerationQuery byOverride(String override, int limit, int offset) {
            return new ModerationQuery(false, false, override, limit, offset);
        }
    }
}
