package dev.kualu.web.moderation;

import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.llm.moderation.Category;
import dev.kualu.llm.moderation.ModerationBlockedException;
import dev.kualu.web.llm.LlmSettingsService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationServiceTest {

    private static final String LEXICON = """
            {
              "categories": {
                "ABUSE": {"severity": "LOW", "terms": [{"pattern": "脏话示例", "type": "exact"}]}
              }
            }
            """;

    private static final String VIOLATION_JSON =
            "{\"category\":\"ABUSE\",\"severity\":\"HIGH\",\"confidence\":0.95,\"reason\":\"x\"}";

    private static ModerationService service(String mode) throws IOException {
        return service(mode, new ModerationProperties.Llm(false, null, null, 0), mock(LlmSettingsService.class),
                new InMemoryModerationEventStore());
    }

    private static ModerationService service(String mode, ModerationProperties.Llm llm,
            LlmSettingsService llmSettings, ModerationEventStore store) throws IOException {
        Path file = Files.createTempFile("lexicon", ".json");
        Files.writeString(file, LEXICON);
        return new ModerationService(
                new ModerationProperties(true, mode, file.toString(), llm),
                store,
                llmSettings);
    }

    private static LlmSettingsService settingsWith(LlmClient client) {
        LlmSettingsService llmSettings = mock(LlmSettingsService.class);
        when(llmSettings.clientForModel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(client);
        return llmSettings;
    }

    @Test
    void flagModeSkipsTheSegmentGate() throws IOException {
        // Gray-release audits nothing per segment: the gate would only chop the stream.
        assertNull(service("flag").segmentGate(text -> {
        }, "ada", "s1"));
    }

    @Test
    void blockModeUsesTheSegmentGate() throws IOException {
        List<String> out = new ArrayList<>();
        var gate = service("block").segmentGate(out::add, "ada", "s1");
        assertNotNull(gate);
        gate.accept("未到句号");
        assertEquals(List.of(), out);
        gate.accept("。");
        assertEquals(List.of("未到句号。"), out);
    }

    @Test
    void flagModeClassifiesL2OffTheRequestPath() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenAnswer(inv -> {
            inFlight.countDown();
            release.await(5, TimeUnit.SECONDS);
            return VIOLATION_JSON;
        });
        InMemoryModerationEventStore store = new InMemoryModerationEventStore();
        ModerationService service = service("flag",
                new ModerationProperties.Llm(true, "p", "m", 800), settingsWith(llm), store);

        // The gate returns without waiting for the classifier verdict.
        String effective = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> service.gateInput(new ConversationMemory(), "ada", "s1", "普通文本"));
        assertEquals("普通文本", effective);

        assertTrue(inFlight.await(2, TimeUnit.SECONDS), "classifier call should be in flight");
        assertTrue(store.list(ModerationEventStore.ModerationQuery.pending(10, 0)).isEmpty(),
                "the request path must not wait for the verdict");

        release.countDown();
        List<ModerationEvent> events = awaitAudit(store);
        assertEquals(1, events.size());
        assertEquals(Category.ABUSE, events.get(0).category());
    }

    @Test
    void blockModeClassifiesL2Inline() throws Exception {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn(VIOLATION_JSON);
        InMemoryModerationEventStore store = new InMemoryModerationEventStore();
        ModerationService service = service("block",
                new ModerationProperties.Llm(true, "p", "m", 2000), settingsWith(llm), store);

        ConversationMemory memory = new ConversationMemory();
        assertThrows(ModerationBlockedException.class,
                () -> service.gateInput(memory, "ada", "s1", "普通文本"));

        List<ModerationEvent> events = store.list(ModerationEventStore.ModerationQuery.pending(10, 0));
        assertEquals(1, events.size(), "block mode must audit before the gate returns");
        assertEquals(Category.ABUSE, events.get(0).category());
    }

    @Test
    void lexiconHitShortCircuitsTheClassifier() throws Exception {
        LlmClient llm = mock(LlmClient.class);
        InMemoryModerationEventStore store = new InMemoryModerationEventStore();
        ModerationService service = service("flag",
                new ModerationProperties.Llm(true, "p", "m", 800), settingsWith(llm), store);

        service.gateInput(new ConversationMemory(), "ada", "s1", "这里有脏话示例");

        verify(llm, never()).chat(anyList());
        assertEquals(1, store.list(ModerationEventStore.ModerationQuery.pending(10, 0)).size(),
                "L1 findings are audited inline");
    }

    /** Polls the audit store until the async L2 finding lands. */
    private static List<ModerationEvent> awaitAudit(ModerationEventStore store) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (store.list(ModerationEventStore.ModerationQuery.pending(10, 0)).isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return store.list(ModerationEventStore.ModerationQuery.pending(10, 0));
    }
}
