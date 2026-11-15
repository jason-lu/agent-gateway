package dev.kualu.web.asr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class MockAsrProviderTest {

    @Test
    void emitsPartialsWhileAudioFlowsAndOneFinalOnFinish() {
        MockAsrProvider provider = new MockAsrProvider();
        List<String> partials = new ArrayList<>();
        List<String> finals = new ArrayList<>();
        provider.start(new StreamingAsrProvider.Listener() {
            @Override
            public void onPartial(String fullText) {
                partials.add(fullText);
            }

            @Override
            public void onFinal(String fullText) {
                finals.add(fullText);
            }

            @Override
            public void onError(String message) {
                fail("unexpected error: " + message);
            }
        });

        provider.sendAudio(new byte[]{0, 1});
        provider.sendAudio(new byte[]{2, 3});
        provider.finish();

        assertFalse(partials.isEmpty());
        // The draft grows towards the final sentence as chunks arrive.
        for (int i = 1; i < partials.size(); i++) {
            assertFalse(partials.get(i).length() < partials.get(i - 1).length());
        }
        assertEquals(List.of(MockAsrProvider.FINAL_TEXT), finals);
    }
}
