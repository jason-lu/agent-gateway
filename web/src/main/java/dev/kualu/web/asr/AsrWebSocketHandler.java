package dev.kualu.web.asr;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Browser-facing ASR stream at {@code /api/v1/asr/stream}. It translates the
 * gateway's JSON/binary protocol into a per-session {@link StreamingAsrProvider}
 * stream and normalizes provider callbacks back into the frame sequence
 * {@code ready → partial* → final → ended}.
 *
 * <p>A {@code start} text frame is optional: binary audio before it is an
 * implicit start with the default language, so a minimal client can just push
 * PCM and send {@code stop}.
 */
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final String STATE_ATTR = AsrWebSocketHandler.class.getName() + ".state";

    private final AsrProperties properties;
    private final AsrProviders providers;
    private final ObjectMapper mapper;

    public AsrWebSocketHandler(AsrProperties properties, AsrProviders providers, ObjectMapper mapper) {
        this.properties = properties;
        this.providers = providers;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        StreamState state = new StreamState();
        session.getAttributes().put(STATE_ATTR, state);
        if (!properties.enabled()) {
            fail(session, state, "ASR is disabled");
        } else if (!providers.isConfigured()) {
            fail(session, state, "ASR provider is not configured");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        StreamState state = state(session);
        if (state == null || state.terminal) {
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(message.getPayload());
        } catch (JacksonException ex) {
            fail(session, state, "unparseable message");
            return;
        }
        switch (root.path("type").asString("")) {
            case "start" -> ensureStarted(session, state, root.path("language").asString(""));
            case "stop" -> stop(session, state);
            default -> fail(session, state, "unknown message type: " + root.path("type").asString(""));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        StreamState state = state(session);
        if (state == null || state.terminal) {
            return;
        }
        // Audio before any start frame is an implicit start with the default language.
        ensureStarted(session, state, "");
        if (state.terminal || !state.started) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        state.provider.sendAudio(pcm);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        cleanup(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session);
    }

    private void ensureStarted(WebSocketSession session, StreamState state, String language) {
        if (state.started || state.terminal) {
            return;
        }
        StreamingAsrProvider provider;
        try {
            provider = providers.open(language);
            provider.start(new AsrListener(session, state));
        } catch (RuntimeException ex) {
            fail(session, state, "ASR provider failed to connect: " + ex.getMessage());
            return;
        }
        state.provider = provider;
        state.started = true;
        send(session, state, frame("ready", null, null));
    }

    private void stop(WebSocketSession session, StreamState state) {
        if (state.started) {
            // The provider flushes and ends with onFinal; the listener finishes the session.
            state.provider.finish();
        } else {
            // Nothing was ever recognized — the contract still wants a clean end.
            finish(session, state, "");
        }
    }

    /** Terminal failure: error frame, then close (no final/ended — the stream died). */
    private void fail(WebSocketSession session, StreamState state, String message) {
        synchronized (state.sendLock) {
            if (state.terminal) {
                return;
            }
            state.terminal = true;
            sendLocked(session, frame("error", "message", message));
        }
        closeSession(session);
    }

    /** Terminal success: final frame exactly once, then ended, then close. */
    private void finish(WebSocketSession session, StreamState state, String fullText) {
        synchronized (state.sendLock) {
            if (state.terminal) {
                return;
            }
            state.terminal = true;
            sendLocked(session, frame("final", "text", fullText));
            sendLocked(session, frame("ended", null, null));
        }
        closeSession(session);
    }

    private void send(WebSocketSession session, StreamState state, String json) {
        synchronized (state.sendLock) {
            sendLocked(session, json);
        }
    }

    private void sendLocked(WebSocketSession session, String json) {
        try {
            session.sendMessage(new TextMessage(json));
        } catch (IOException | IllegalStateException ex) {
            // Transport is gone; afterConnectionClosed drives cleanup.
        }
    }

    private void closeSession(WebSocketSession session) {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException ex) {
            // Already gone.
        }
    }

    private void cleanup(WebSocketSession session) {
        StreamState state = state(session);
        if (state == null) {
            return;
        }
        synchronized (state.sendLock) {
            state.terminal = true;
        }
        if (state.provider != null) {
            state.provider.close();
        }
    }

    private static StreamState state(WebSocketSession session) {
        return (StreamState) session.getAttributes().get(STATE_ATTR);
    }

    /** One JSON event frame; null fields are left out of the payload entirely. */
    private String frame(String type, String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        if (key != null) {
            node.put(key, value);
        }
        return node.toString();
    }

    private static final class StreamState {
        final Object sendLock = new Object();
        StreamingAsrProvider provider;
        volatile boolean started;
        volatile boolean terminal;
    }

    private final class AsrListener implements StreamingAsrProvider.Listener {

        private final WebSocketSession session;
        private final StreamState state;

        AsrListener(WebSocketSession session, StreamState state) {
            this.session = session;
            this.state = state;
        }

        @Override
        public void onPartial(String fullText) {
            if (state.terminal) {
                return;
            }
            send(session, state, frame("partial", "text", fullText));
        }

        @Override
        public void onFinal(String fullText) {
            finish(session, state, fullText);
        }

        @Override
        public void onError(String message) {
            fail(session, state, message);
        }
    }
}
