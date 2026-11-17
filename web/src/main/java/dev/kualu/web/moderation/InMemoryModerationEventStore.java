package dev.kualu.web.moderation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local audit log for {@code agent.store=memory}; lost on restart. */
public class InMemoryModerationEventStore implements ModerationEventStore {

    private final AtomicLong sequence = new AtomicLong();
    private final List<ModerationEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public ModerationEvent record(ModerationEvent.New event) {
        ModerationEvent stored = new ModerationEvent(
                sequence.incrementAndGet(), Instant.now(),
                event.owner(), event.sessionId(), event.direction(), event.category(),
                event.severity(), event.action(), event.detector(), event.excerpt(),
                event.textSha256(), event.needsReview(), false, null, null);
        events.add(stored);
        return stored;
    }

    @Override
    public List<ModerationEvent> list(ModerationQuery query) {
        List<ModerationEvent> matched = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0; i--) {
            ModerationEvent event = events.get(i);
            if (query.unreviewedOnly() && event.reviewed()) {
                continue;
            }
            if (query.needsReviewOnly() && !event.needsReview()) {
                continue;
            }
            if (query.override() != null && !query.override().equals(event.reviewOverride())) {
                continue;
            }
            matched.add(event);
        }
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return List.copyOf(matched.subList(from, to));
    }

    @Override
    public Optional<ModerationEvent> find(long id) {
        return events.stream().filter(event -> event.id() == id).findFirst();
    }

    @Override
    public boolean review(long id, String note, String override) {
        for (int i = 0; i < events.size(); i++) {
            ModerationEvent event = events.get(i);
            if (event.id() != id) {
                continue;
            }
            events.set(i, new ModerationEvent(
                    event.id(), event.createdAt(), event.owner(), event.sessionId(),
                    event.direction(), event.category(), event.severity(), event.action(),
                    event.detector(), event.excerpt(), event.textSha256(), event.needsReview(),
                    true, note, override));
            return true;
        }
        return false;
    }
}
