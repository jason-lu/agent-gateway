package dev.kualu.llm.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnTimingsTest {

    @AfterEach
    void tearDown() {
        TurnTimings.clear();
    }

    @Test
    void unboundRecordersAreNoOps() {
        assertNull(TurnTimings.current());
        TurnTimings.recordLoad(1, 2, 3, 4);
        TurnTimings.recordList(1, 2, 3);
        TurnTimings.recordCompress(1, true, 2);
        TurnTimings.recordLlmRound(1);
        TurnTimings.recordFirstDelta();
        TurnTimings.recordPersist(1, 2, 3);
        assertNull(TurnTimings.current());
    }

    @Test
    void recordsSegmentsAndFormatsLogLine() {
        TurnTimings timings = TurnTimings.begin();
        TurnTimings.recordLoad(10, 20, 3000, 42);
        TurnTimings.recordList(5, 6, 7000);
        TurnTimings.recordCompress(15, true, 800);
        TurnTimings.recordLlmRound(100);
        TurnTimings.recordLlmRound(200);
        TurnTimings.recordFirstDelta();
        TurnTimings.recordPersist(30, 40, 5000);

        String line = timings.toLogLine();
        assertTrue(line.contains("load=30ms(sql=10,decode=20,chars=3000,count=42)"), line);
        assertTrue(line.contains("list=11ms(sql=5,decode=6,chars=7000)"), line);
        assertTrue(line.contains("compress=fired(15ms,summaryChars=800)"), line);
        assertTrue(line.contains("llm=300ms(ttft="), line);
        assertTrue(line.contains("rounds=2)"), line);
        assertTrue(line.contains("persist=70ms(encode=30,write=40,chars=5000)"), line);
        assertTrue(timings.totalMs() >= 0);
    }

    @Test
    void skippedCompressAndMissingSegmentsAreVisible() {
        TurnTimings timings = TurnTimings.begin();
        TurnTimings.recordCompress(1, false, 0);

        String line = timings.toLogLine();
        assertTrue(line.contains("compress=skipped(1ms,summaryChars=0)"), line);
        assertTrue(line.contains("load=-"), line);
        assertTrue(line.contains("list=-"), line);
        assertTrue(line.contains("llm=-"), line);
        assertTrue(line.contains("persist=-"), line);
    }

    @Test
    void toolsAreAggregatedPerName() {
        TurnTimings timings = TurnTimings.begin();
        TurnTimings.recordTool("exec", 300);
        TurnTimings.recordTool("exec", 200);
        TurnTimings.recordTool("mcp_fetch", 50);

        assertEquals(3, timings.toolCalls());
        assertEquals(550, timings.toolMs());
        assertEquals(2, timings.toolBreakdown().get("exec")[0]);
        assertEquals(500, timings.toolBreakdown().get("exec")[1]);

        String line = timings.toLogLine();
        assertTrue(line.contains("tools=550ms(count=3:exec=500,mcp_fetch=50)"), line);
    }

    @Test
    void missingToolSegmentPrintsDash() {
        TurnTimings timings = TurnTimings.begin();
        assertTrue(timings.toLogLine().contains("tools=-"));
    }

    @Test
    void firstDeltaIsRecordedOnlyOnce() {
        TurnTimings timings = TurnTimings.begin();
        TurnTimings.recordFirstDelta();
        long first = timings.llmTtftMs();
        TurnTimings.recordFirstDelta();
        assertEquals(first, timings.llmTtftMs());
        assertTrue(first >= 0);
    }

    @Test
    void unstreamedTurnHasNoTtft() {
        TurnTimings timings = TurnTimings.begin();
        TurnTimings.recordLlmRound(50);
        assertEquals(-1, timings.llmTtftMs());
    }

    @Test
    void clearUnbinds() {
        TurnTimings.begin();
        TurnTimings.clear();
        assertNull(TurnTimings.current());
    }

    @Test
    void workerThreadInheritsTheCollector() throws Exception {
        TurnTimings timings = TurnTimings.begin();
        AtomicLong seenMs = new AtomicLong(-1);
        Thread worker = new Thread(() -> {
            TurnTimings.recordLlmRound(5);
            TurnTimings current = TurnTimings.current();
            seenMs.set(current == null ? -1 : current.llmMs());
            TurnTimings.clear();
        });
        worker.start();
        worker.join();
        assertEquals(5, seenMs.get());
        assertEquals(5, timings.llmMs());
        assertFalse(TurnTimings.current() == null, "parent keeps its own binding");
    }
}
