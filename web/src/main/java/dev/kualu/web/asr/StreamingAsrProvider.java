package dev.kualu.web.asr;

/**
 * One recognition stream against a streaming ASR upstream. Instances are not
 * reusable: open a fresh provider per WebSocket session via {@link AsrProviders}.
 *
 * <p>Callbacks may arrive on the upstream's IO thread; implementations must not
 * assume they run on the caller thread. Terminal events are delivered at most
 * once per stream: either {@link Listener#onFinal} or {@link Listener#onError}.
 */
public interface StreamingAsrProvider {

    /**
     * Connects the upstream and starts one recognition stream. Returns once the
     * upstream is connected and ready for audio; a failed connection surfaces as
     * an unchecked exception rather than a callback, so callers can report it
     * before anything was sent downstream.
     */
    void start(Listener listener);

    /** Feeds one PCM16LE mono 16 kHz chunk (~40 ms) to the recognizer. */
    void sendAudio(byte[] pcm16leMono16k);

    /**
     * No more audio is coming. The stream ends with exactly one
     * {@link Listener#onFinal} once the upstream has flushed — with empty text
     * when nothing was recognized.
     */
    void finish();

    /** Aborts the stream immediately; no further callbacks are delivered. */
    void close();

    /** Normalized events for one stream; {@code fullText} values are complete drafts. */
    interface Listener {

        /** The full current draft text; callers replace their draft wholesale. */
        void onPartial(String fullText);

        /** The full final text, exactly once per stream (empty when nothing matched). */
        void onFinal(String fullText);

        /** The stream is dead; no {@link #onFinal} follows. */
        void onError(String message);
    }
}
