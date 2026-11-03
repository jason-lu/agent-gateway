package dev.kualu.llm;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Guards a lazily-consumed upstream line stream against stalls: if no line arrives
 * within the idle timeout, the underlying stream is closed — which wakes the blocked
 * reader — and the consumer sees a {@link LlmException} instead of a silent hang.
 *
 * <p>Activity is counted per raw SSE line, not per content delta: reasoning models
 * stream {@code reasoning_content} while thinking, which the delta parser filters
 * out, so a quiet answer pane does not mean a dead upstream.
 */
final class IdleWatchdog {

    static final String STALL_MESSAGE = "大模型长时间未响应，已中断本次回答";

    private static final long POLL_INTERVAL_MS = 250;

    private final Stream<String> lines;
    private final long idleNanos;
    private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean stalled = new AtomicBoolean();
    private final Thread watcher;

    IdleWatchdog(Stream<String> lines, Duration idleTimeout) {
        this.lines = lines;
        this.idleNanos = idleTimeout.toNanos();
        this.watcher = new Thread(this::watchLoop, "llm-idle-watchdog");
        this.watcher.setDaemon(true);
    }

    /** Returns {@code lines} plus stall detection; the result must be closed as usual. */
    Stream<String> watch() {
        watcher.start();
        Stream<String> guarded = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(wrapped(lines.iterator()), Spliterator.ORDERED),
                false);
        return guarded.onClose(this::close);
    }

    private Iterator<String> wrapped(Iterator<String> raw) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                try {
                    boolean more = raw.hasNext();
                    if (more) {
                        lastActivity.set(System.nanoTime());
                    }
                    return more;
                } catch (UncheckedIOException ex) {
                    throw translate(ex);
                }
            }

            @Override
            public String next() {
                try {
                    String line = raw.next();
                    lastActivity.set(System.nanoTime());
                    return line;
                } catch (UncheckedIOException ex) {
                    throw translate(ex);
                }
            }
        };
    }

    private RuntimeException translate(UncheckedIOException ex) {
        return stalled.get() ? new LlmException(STALL_MESSAGE, ex) : ex;
    }

    private void watchLoop() {
        while (!stopped.get() && !stalled.get()) {
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (System.nanoTime() - lastActivity.get() > idleNanos) {
                if (stalled.compareAndSet(false, true)) {
                    closeQuietly();
                }
                return;
            }
        }
    }

    private void close() {
        if (stopped.compareAndSet(false, true)) {
            watcher.interrupt();
        }
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            lines.close();
        } catch (RuntimeException ignored) {
            // the stream may already have failed; the reader is unblocked either way
        }
    }
}
