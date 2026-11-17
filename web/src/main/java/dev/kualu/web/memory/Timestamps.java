package dev.kualu.web.memory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Strictly increasing timestamps for memory rows. Two saves inside one clock
 * tick would otherwise share an {@code updated_at} and make "newest first"
 * ordering nondeterministic — an edited entry must reliably bump to the top.
 */
final class Timestamps {

    private static final AtomicReference<Instant> LAST = new AtomicReference<>(Instant.EPOCH);

    static Instant next() {
        return LAST.updateAndGet(last -> {
            Instant now = Instant.now();
            return now.isAfter(last) ? now : last.plusNanos(1);
        });
    }

    private Timestamps() {
    }
}
