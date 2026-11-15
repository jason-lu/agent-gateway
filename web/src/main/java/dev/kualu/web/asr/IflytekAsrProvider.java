package dev.kualu.web.asr;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 讯飞 IAT streaming recognizer over their real-time WebSocket API, using the
 * JDK HTTP client so no extra dependency is pulled in.
 *
 * <p>Upstream auth is a signed URL (HMAC-SHA256 over the RFC 1123 date and the
 * request line, keyed by the API secret); audio goes out as base64 JSON frames
 * and results come back with wpgs dynamic revisions, normalized by
 * {@link IatResultAccumulator}.
 */
public class IflytekAsrProvider implements StreamingAsrProvider {

    static final String HOST = "iat-api.xfyun.cn";
    static final String REQUEST_LINE = "GET /v1/iat HTTP/1.1";
    static final String WS_URI = "wss://iat-api.xfyun.cn/v1/iat";

    private static final String AUDIO_FORMAT = "audio/L16;rate=16000";
    private static final String AUDIO_ENCODING = "raw";
    private static final long CONNECT_TIMEOUT_SECONDS = 10;

    private final ObjectMapper mapper;
    private final String appId;
    private final String apiKey;
    private final String apiSecret;
    private final String language;
    private final long vadEosMs;

    private final IatResultAccumulator accumulator = new IatResultAccumulator();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final Object sendLock = new Object();
    private CompletableFuture<?> sendTail = CompletableFuture.completedFuture(null);

    private volatile Listener listener;
    private volatile WebSocket webSocket;
    private volatile boolean sentAnyFrame;
    private volatile boolean finished;

    public IflytekAsrProvider(
            ObjectMapper mapper,
            String appId,
            String apiKey,
            String apiSecret,
            String language,
            long vadEosMs) {
        this.mapper = mapper;
        this.appId = appId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.language = language;
        this.vadEosMs = vadEosMs;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        try {
            this.webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(buildUri(apiKey, apiSecret, Instant.now()), new IatSocketListener())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while connecting to iflytek IAT", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("iflytek IAT connection failed", ex);
        }
    }

    @Override
    public void sendAudio(byte[] pcm16leMono16k) {
        // Audio after finish() would follow the end-of-stream frame — drop it.
        if (terminated.get() || finished) {
            return;
        }
        ObjectNode frame;
        if (!sentAnyFrame) {
            sentAnyFrame = true;
            frame = firstFrame(pcm16leMono16k);
        } else {
            frame = middleFrame(pcm16leMono16k);
        }
        enqueueSend(frame.toString());
    }

    @Override
    public void finish() {
        if (terminated.get() || finished) {
            return;
        }
        finished = true;
        if (!sentAnyFrame) {
            // Nothing ever reached the upstream, so no result can come back:
            // close the protocol with the empty final the contract promises.
            emitFinal("");
            return;
        }
        ObjectNode frame = mapper.createObjectNode();
        ObjectNode data = frame.putObject("data");
        data.put("status", 2);
        data.put("audio", "");
        enqueueSend(frame.toString());
    }

    @Override
    public void close() {
        terminated.set(true);
        WebSocket socket = webSocket;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (RuntimeException ex) {
                // Already closed — aborting must never fail the caller.
            }
        }
    }

    /**
     * Signs a request for the IAT endpoint: base64 of the HMAC-SHA256 of the
     * canonical {@code host}/{@code date}/{@code request-line} triple, keyed by
     * the API secret. Deterministic in (secret, date) so it can be tested
     * without golden hashes.
     */
    static String sign(String apiSecret, String date) {
        String origin = "host: " + HOST + "\ndate: " + date + "\n" + REQUEST_LINE;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(origin.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    /** Builds the upstream URI: signature plus the {@code date}/{@code host} the signature covers. */
    static URI buildUri(String apiKey, String apiSecret, Instant instant) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC));
        String authorizationOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", signature=\"" + sign(apiSecret, date) + "\"";
        // iFlytek expects the whole authorization origin base64-wrapped, exactly as its
        // official demos do it — sending the bare origin fails the handshake.
        String authorization = Base64.getEncoder()
                .encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));
        return URI.create(WS_URI
                + "?authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8)
                + "&host=" + HOST);
    }

    /** iFlytek language tags look like {@code zh_cn}; the gateway speaks {@code zh-CN}. */
    private static String upstreamLanguage(String language) {
        return language == null ? "zh_cn" : language.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private ObjectNode firstFrame(byte[] pcm) {
        ObjectNode frame = mapper.createObjectNode();
        ObjectNode common = frame.putObject("common");
        common.put("app_id", appId);
        ObjectNode business = frame.putObject("business");
        business.put("language", upstreamLanguage(language));
        business.put("domain", "iat");
        business.put("accent", "mandarin");
        business.put("vad_eos", vadEosMs);
        // wpgs delivers the dynamic-revision stream (apd/rpl frames) the accumulator expects.
        business.put("dwa", "wpgs");
        business.put("ptt", 1);
        ObjectNode data = frame.putObject("data");
        data.put("status", 0);
        data.put("format", AUDIO_FORMAT);
        data.put("encoding", AUDIO_ENCODING);
        data.put("audio", Base64.getEncoder().encodeToString(pcm));
        return frame;
    }

    private ObjectNode middleFrame(byte[] pcm) {
        ObjectNode frame = mapper.createObjectNode();
        ObjectNode data = frame.putObject("data");
        data.put("status", 1);
        data.put("format", AUDIO_FORMAT);
        data.put("encoding", AUDIO_ENCODING);
        data.put("audio", Base64.getEncoder().encodeToString(pcm));
        return frame;
    }

    /**
     * JDK WebSockets allow only one send in flight; chain them so audio chunks
     * and control frames never race each other off the socket.
     */
    private void enqueueSend(String json) {
        synchronized (sendLock) {
            sendTail = sendTail
                    .handle((ok, ex) -> null)
                    .thenCompose(ignored -> webSocket.sendText(json, true));
        }
    }

    private void handleUpstream(String message) {
        JsonNode root;
        try {
            root = mapper.readTree(message);
        } catch (JacksonException ex) {
            emitError("iflytek IAT: undecodable response");
            return;
        }
        int code = root.path("code").asInt(0);
        if (code != 0) {
            String upstreamMessage = root.path("message").asString("");
            emitError(upstreamMessage.isEmpty() ? "iflytek IAT error code " + code : upstreamMessage);
            return;
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return;
        }
        JsonNode result = data.path("result");
        if (result.isObject()) {
            emitPartial(accumulator.accept(IatResultAccumulator.parse(result)));
        }
        if (data.path("status").asInt(-1) == 2) {
            emitFinal(accumulator.fullText());
        }
    }

    private void emitPartial(String fullText) {
        if (!terminated.get()) {
            listener.onPartial(fullText);
        }
    }

    private void emitFinal(String fullText) {
        if (terminated.compareAndSet(false, true)) {
            listener.onFinal(fullText);
        }
    }

    private void emitError(String message) {
        if (terminated.compareAndSet(false, true)) {
            listener.onError(message);
        }
    }

    private final class IatSocketListener implements WebSocket.Listener {

        private final StringBuilder pending = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) {
                String message = pending.toString();
                pending.setLength(0);
                handleUpstream(message);
            }
            socket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            emitError("iflytek IAT: " + (error.getMessage() == null ? error : error.getMessage()));
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            // The upstream hanging up is the end-of-stream signal when no explicit
            // status:2 frame arrived — the stream still owes one final.
            if (sentAnyFrame) {
                emitFinal(accumulator.fullText());
            }
            return null;
        }
    }
}
