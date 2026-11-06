package dev.kualu.web.metrics;

import dev.kualu.llm.metrics.TurnTimings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TurnMetricsTest {

    @Test
    void completeRecordsEverySegmentUnderTheEndpointTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TurnMetrics metrics = new TurnMetrics(registry);

        TurnTimings timings = TurnTimings.begin();
        TurnTimings.recordLoad(1, 2, 3000, 42);
        TurnTimings.recordCompress(3, true, 500);
        TurnTimings.recordLlmRound(10);
        TurnTimings.recordLlmRound(20);
        TurnTimings.recordFirstDelta();
        TurnTimings.recordTool("exec", 300);
        TurnTimings.recordTool("exec", 200);
        TurnTimings.recordPersist(4, 5, 6000);
        TurnTimings.clear();

        metrics.complete("send", "sess-1", timings);

        assertNotNull(registry.find("agent.turn.total").tag("endpoint", "send").timer());
        assertNotNull(registry.find("agent.turn.load").tag("endpoint", "send").timer());
        assertNotNull(registry.find("agent.turn.compress").tag("fired", "true").timer());
        assertNotNull(registry.find("agent.turn.llm").tag("endpoint", "send").timer());
        assertNotNull(registry.find("agent.turn.llm.ttft").tag("endpoint", "send").timer());
        assertNotNull(registry.find("agent.turn.persist").tag("endpoint", "send").timer());
        assertNotNull(registry.find("agent.turn.tools").tag("endpoint", "send").timer());
        assertNotNull(registry.find("agent.turn.tool").tag("tool", "exec").timer());
        assertEquals(500, registry.get("agent.turn.tools").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        assertEquals(2, registry.get("agent.turn.tools.count").summary().totalAmount());
        assertEquals(2, registry.get("agent.turn.llm.rounds").summary().totalAmount());
        assertEquals(3000 + 6000,
                registry.get("agent.turn.payload.read").summary().totalAmount()
                        + registry.get("agent.turn.payload.write").summary().totalAmount());
        assertEquals(42, registry.get("agent.turn.messages").summary().totalAmount());
    }

    @Test
    void missingSegmentsRegisterNoMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TurnMetrics metrics = new TurnMetrics(registry);

        TurnTimings timings = TurnTimings.begin();
        TurnTimings.clear();

        metrics.complete("history", "sess-2", timings);

        assertNotNull(registry.find("agent.turn.total").timer());
        assertNull(registry.find("agent.turn.load").timer());
        assertNull(registry.find("agent.turn.llm").timer());
        assertNull(registry.find("agent.turn.persist").timer());
    }
}
