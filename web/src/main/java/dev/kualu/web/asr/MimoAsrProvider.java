package dev.kualu.web.asr;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * Xiaomi MiMo ASR ({@code mimo-v2.5-asr}) — a batch recognizer dressed in the
 * streaming interface: it accepts a whole recording ({@code wav} base64 in an
 * OpenAI-style {@code chat.completions} call with an {@code input_audio} part)
 * and answers with the transcript. There is no audio-in streaming and no interim
 * hypotheses, so chunks are buffered and only {@link Listener#onFinal} fires,
 * after {@link #finish()} and the upstream round trip. {@code onPartial} is
 * never called — consumers should treat the draft as "pending" until the final.
 */
public class MimoAsrProvider implements StreamingAsrProvider {

    static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";
    static final String DEFAULT_MODEL = "mimo-v2.5-asr";

    /** MiMo caps the base64 payload at 10 MB — ~7 MB of PCM16, ~3.6 min at 16 kHz. */
    private static final int MAX_PCM_BYTES = 7 * 1024 * 1024;

    private static final int SAMPLE_RATE = 16000;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final ObjectMapper mapper;
    private final String apiKey;
    private final URI endpoint;
    private final String model;
    private final String language;

    private final Object lock = new Object();
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private Listener listener;
    private boolean terminal;

    MimoAsrProvider(ObjectMapper mapper, String apiKey, String baseUrl, String model, String language) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = URI.create(base + "/chat/completions");
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        this.language = language;
    }

    @Override
    public void start(Listener listener) {
        synchronized (lock) {
            this.listener = listener;
        }
    }

    @Override
    public void sendAudio(byte[] pcm16leMono16k) {
        synchronized (lock) {
            if (terminal) {
                return;
            }
            if (pcm.size() + pcm16leMono16k.length > MAX_PCM_BYTES) {
                terminal = true;
                listener.onError("audio too long: MiMo ASR accepts up to ~3.5 minutes per request");
                return;
            }
            pcm.writeBytes(pcm16leMono16k);
        }
    }

    @Override
    public void finish() {
        byte[] wav;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            wav = wavOf(pcm.toByteArray());
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(wav), StandardCharsets.UTF_8))
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(this::handleResponse)
                .exceptionally(failure -> {
                    fail(failure.getMessage() == null ? "MiMo ASR request failed" : failure.getMessage());
                    return null;
                });
    }

    @Override
    public void close() {
        synchronized (lock) {
            terminal = true;
        }
    }

    private void handleResponse(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            fail("MiMo ASR HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
            return;
        }
        succeed(extractText(response.body()));
    }

    /** Builds one {@code chat.completions} body carrying the recording as {@code input_audio}. */
    String requestJson(byte[] wav) {
        String dataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        ObjectNode part = message.putArray("content").addObject();
        part.put("type", "input_audio");
        part.putObject("input_audio").put("data", dataUrl);
        // The SDK's extra_body lands at the request root — sibling of model/messages.
        root.putObject("asr_options").put("language", upstreamLanguage(language));
        return root.toString();
    }

    /** The transcript is the assistant message: a plain string, or text parts. */
    private String extractText(String body) {
        JsonNode content = mapper.readTree(body)
                .path("choices").path(0).path("message").path("content");
        if (content.isString()) {
            return content.asString("");
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : (ArrayNode) content) {
                text.append(part.path("text").asString(""));
            }
            return text.toString();
        }
        return "";
    }

    private void succeed(String text) {
        Listener target;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            terminal = true;
            target = listener;
        }
        if (target != null) {
            target.onFinal(text);
        }
    }

    private void fail(String message) {
        Listener target;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            terminal = true;
            target = listener;
        }
        if (target != null) {
            target.onError(message);
        }
    }

    /** MiMo speaks {@code auto}/{@code zh}/{@code en}; the gateway speaks BCP 47. */
    static String upstreamLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "auto";
        }
        String lower = language.toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh")) {
            return "zh";
        }
        if (lower.startsWith("en")) {
            return "en";
        }
        return "auto";
    }

    /** Wraps raw PCM16LE mono into the 44-byte RIFF/WAVE container MiMo accepts. */
    static byte[] wavOf(byte[] pcm) {
        byte[] wav = new byte[44 + pcm.length];
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + pcm.length);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1); // PCM
        buffer.putShort((short) 1); // mono
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(SAMPLE_RATE * 2); // byte rate: 16-bit mono
        buffer.putShort((short) 2); // block align
        buffer.putShort((short) 16); // bits per sample
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(pcm.length);
        buffer.put(pcm);
        return wav;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
