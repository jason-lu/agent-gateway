package dev.kualu.llm.moderation;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.client.LlmConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmModeratorTest {

    static final class StubLlm extends LlmClient {
        final List<List<ChatMessage>> calls = new ArrayList<>();
        String reply = """
                {"category":"NONE","severity":"LOW","confidence":1.0,"reason":"clean"}
                """;
        RuntimeException failure;
        long delayMs;

        StubLlm() {
            super(new LlmConfig("http://localhost", "key", "model"));
        }

        @Override
        public String chat(List<ChatMessage> messages, Integer maxTokens) {
            calls.add(List.copyOf(messages));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    private static LlmModerator moderator(StubLlm llm, ExecutorService executor) {
        return new LlmModerator(() -> llm, Duration.ofMillis(500), 0.7,
                new ModerationPolicy(Mode.BLOCK), executor);
    }

    private static ExecutorService executor() {
        return Executors.newSingleThreadExecutor();
    }

    @Test
    void cleanVerdictYieldsNoFinding() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            assertTrue(moderator(llm, executor).check("普通文本", Direction.INPUT).isEmpty());
            assertEquals(1, llm.calls.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void violationVerdictBecomesBlockedFinding() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            llm.reply = """
                    {"category":"VIOLENCE","severity":"HIGH","confidence":0.95,"reason":"clear incitement"}
                    """;
            ModerationHit hit = moderator(llm, executor).check("某段文本", Direction.INPUT).orElseThrow();
            assertEquals(Category.VIOLENCE, hit.category());
            assertEquals(Action.BLOCK, hit.action());
            assertFalse(hit.hasSpans());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unsureVerdictFlagsInsteadOfBlocking() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            llm.reply = """
                    {"category":"REACTIONARY","severity":"HIGH","confidence":0.4,"reason":"barely"}
                    """;
            ModerationHit hit = moderator(llm, executor).check("某段文本", Direction.INPUT).orElseThrow();
            assertEquals(Action.FLAG, hit.action());
            assertTrue(new ModerationPolicy(Mode.BLOCK).needsReview(hit));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void semanticLowSeverityDegradesRedactToFlag() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            llm.reply = """
                    {"category":"ABUSE","severity":"LOW","confidence":0.9,"reason":"rude"}
                    """;
            // LOW maps to REDACT in block mode, but a semantic finding has no
            // span to mask — the action degrades to FLAG.
            ModerationHit hit = moderator(llm, executor).check("某段文本", Direction.INPUT).orElseThrow();
            assertEquals(Action.FLAG, hit.action());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void malformedReplyDegradesToNoFinding() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            llm.reply = "抱歉，我无法输出 JSON。";
            assertTrue(moderator(llm, executor).check("文本", Direction.INPUT).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void upstreamFailureDegradesToNoFinding() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            llm.failure = new RuntimeException("upstream down");
            assertTrue(moderator(llm, executor).check("文本", Direction.INPUT).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutDegradesToNoFinding() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            llm.delayMs = 2_000;
            assertTrue(moderator(llm, executor).check("文本", Direction.INPUT).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void circuitOpensAfterConsecutiveFailures() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            llm.failure = new RuntimeException("down");
            LlmModerator moderator = moderator(llm, executor);
            for (int i = 0; i < 5; i++) {
                assertTrue(moderator.check("文本", Direction.INPUT).isEmpty());
            }
            // After the breaker opens the classifier is not called again.
            int callsSoFar = llm.calls.size();
            assertTrue(moderator.check("文本", Direction.INPUT).isEmpty());
            assertEquals(callsSoFar, llm.calls.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nullClientDegradesToNoFinding() {
        ExecutorService executor = executor();
        try {
            LlmModerator moderator = new LlmModerator(() -> null, Duration.ofMillis(500), 0.7,
                    new ModerationPolicy(Mode.BLOCK), executor);
            assertTrue(moderator.check("文本", Direction.INPUT).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void parseVerdictReadsJson() {
        ModerationVerdict verdict = LlmModerator.parseVerdict(
                "说明文字 {\"category\":\"CRIME\",\"severity\":\"HIGH\",\"confidence\":0.8,\"reason\":\"x\"} 尾部");
        assertEquals(Category.CRIME, verdict.category());
        assertEquals(Severity.HIGH, verdict.severity());
        assertEquals(0.8, verdict.confidence());
    }

    @Test
    void longInputTruncates() {
        ExecutorService executor = executor();
        try {
            StubLlm llm = new StubLlm();
            moderator(llm, executor).check("字".repeat(LlmModerator.MAX_INPUT_CHARS + 500), Direction.INPUT);
            String userPayload = llm.calls.get(0).get(1).content();
            assertFalse(userPayload.length() > LlmModerator.MAX_INPUT_CHARS + 40);
        } finally {
            executor.shutdownNow();
        }
    }
}
