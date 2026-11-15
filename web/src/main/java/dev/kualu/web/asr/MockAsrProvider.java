package dev.kualu.web.asr;

import java.util.List;

/**
 * Keyless stand-in for development and tests: it grows a canned sentence as
 * audio arrives so the UI's draft state can be exercised, then "recognizes" the
 * whole sentence on {@link #finish()}.
 */
public class MockAsrProvider implements StreamingAsrProvider {

    static final String FINAL_TEXT = "这是一条模拟识别结果";

    /** Growing prefixes of {@link #FINAL_TEXT}, one per audio chunk. */
    private static final List<String> PREFIXES = List.of("这是一条", "这是一条模拟识别结");

    private Listener listener;
    private int chunks;
    private boolean ended;

    @Override
    public void start(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void sendAudio(byte[] pcm16leMono16k) {
        if (ended) {
            return;
        }
        listener.onPartial(PREFIXES.get(Math.min(chunks, PREFIXES.size() - 1)));
        chunks++;
    }

    @Override
    public void finish() {
        if (ended) {
            return;
        }
        ended = true;
        listener.onFinal(FINAL_TEXT);
    }

    @Override
    public void close() {
        ended = true;
    }
}
