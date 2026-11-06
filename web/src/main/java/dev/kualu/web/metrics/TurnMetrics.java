package dev.kualu.web.metrics;

import dev.kualu.llm.metrics.TurnTimings;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Second exit of the turn-timing collector: writes the one-line
 * {@code turn-timing} log and records the same segments into Micrometer.
 * Session ids stay in the log only — metric tags carry the endpoint so tag
 * cardinality cannot grow with conversation count.
 */
@Component
public class TurnMetrics {

    private static final Logger log = LoggerFactory.getLogger(TurnMetrics.class);

    private final MeterRegistry registry;

    public TurnMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void complete(String endpoint, String sessionId, TurnTimings timings) {
        log.info("turn-timing endpoint={} session={} {}", endpoint, sessionId, timings.toLogLine());

        String[] tag = {"endpoint", endpoint};
        timer("agent.turn.total", tag).record(Duration.ofMillis(timings.totalMs()));

        if (timings.hasLoad()) {
            timer("agent.turn.load", tag).record(Duration.ofMillis(timings.loadMs()));
            summary("agent.turn.payload.read", tag).record(timings.loadChars());
            summary("agent.turn.messages", tag).record(timings.loadCount());
        }
        if (timings.hasList()) {
            timer("agent.turn.list", tag).record(Duration.ofMillis(timings.listMs()));
            summary("agent.turn.payload.read", tag).record(timings.listChars());
        }
        if (timings.hasCompress()) {
            registry.timer("agent.turn.compress",
                            "endpoint", endpoint, "fired", String.valueOf(timings.compressFired()))
                    .record(Duration.ofMillis(timings.compressMs()));
        }
        if (timings.llmRounds() > 0) {
            timer("agent.turn.llm", tag).record(Duration.ofMillis(timings.llmMs()));
            summary("agent.turn.llm.rounds", tag).record(timings.llmRounds());
            if (timings.llmTtftMs() >= 0) {
                timer("agent.turn.llm.ttft", tag).record(Duration.ofMillis(timings.llmTtftMs()));
            }
        }
        if (timings.toolCalls() > 0) {
            timer("agent.turn.tools", tag).record(Duration.ofMillis(timings.toolMs()));
            summary("agent.turn.tools.count", tag).record(timings.toolCalls());
            timings.toolBreakdown().forEach((name, entry) ->
                    registry.timer("agent.turn.tool", "endpoint", endpoint, "tool", name)
                            .record(Duration.ofMillis(entry[1])));
        }
        if (timings.hasPersist()) {
            timer("agent.turn.persist", tag).record(Duration.ofMillis(timings.persistMs()));
            summary("agent.turn.payload.write", tag).record(timings.persistChars());
        }
    }

    private Timer timer(String name, String... tags) {
        return registry.timer(name, tags);
    }

    private DistributionSummary summary(String name, String... tags) {
        return registry.summary(name, tags);
    }
}
