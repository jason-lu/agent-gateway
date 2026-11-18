package dev.kualu.web.moderation;

import dev.kualu.web.llm.LlmSettingsService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class ModerationServiceTest {

    private static final String LEXICON = """
            {
              "categories": {
                "ABUSE": {"severity": "LOW", "terms": [{"pattern": "脏话示例", "type": "exact"}]}
              }
            }
            """;

    private static ModerationService service(String mode) throws IOException {
        Path file = Files.createTempFile("lexicon", ".json");
        Files.writeString(file, LEXICON);
        return new ModerationService(
                new ModerationProperties(true, mode, file.toString(),
                        new ModerationProperties.Llm(false, null, null, 0)),
                new InMemoryModerationEventStore(),
                mock(LlmSettingsService.class));
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
}
