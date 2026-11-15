package dev.kualu.web.asr;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MimoAsrProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void wavOfWrapsPcmInRiffHeader() {
        byte[] pcm = new byte[] {1, 2, 3, 4};
        byte[] wav = MimoAsrProvider.wavOf(pcm);

        assertEquals(44 + pcm.length, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));

        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(36 + pcm.length, buffer.getInt(4)); // RIFF chunk size
        assertEquals(16, buffer.getInt(16)); // fmt chunk size
        assertEquals(1, buffer.getShort(20)); // PCM
        assertEquals(1, buffer.getShort(22)); // mono
        assertEquals(16000, buffer.getInt(24));
        assertEquals(32000, buffer.getInt(28)); // byte rate: 16-bit mono
        assertEquals(2, buffer.getShort(32)); // block align
        assertEquals(16, buffer.getShort(34)); // bits per sample
        assertEquals(pcm.length, buffer.getInt(40));
        assertArrayEquals(pcm, Arrays.copyOfRange(wav, 44, wav.length));
    }

    @Test
    void upstreamLanguageMapsBcp47ToMimoTags() {
        assertEquals("zh", MimoAsrProvider.upstreamLanguage("zh-CN"));
        assertEquals("en", MimoAsrProvider.upstreamLanguage("en-US"));
        assertEquals("auto", MimoAsrProvider.upstreamLanguage("yue-HK"));
        assertEquals("auto", MimoAsrProvider.upstreamLanguage(""));
        assertEquals("auto", MimoAsrProvider.upstreamLanguage(null));
    }

    @Test
    void finishPostsWavAndDeliversTranscript() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":"你好，世界"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            MimoAsrProvider provider =
                    new MimoAsrProvider(MAPPER, "the-key", baseUrl, "mimo-v2.5-asr", "zh-CN");
            AtomicReference<String> finalText = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            provider.start(new StreamingAsrProvider.Listener() {
                @Override
                public void onPartial(String fullText) {
                    // MiMo is batch: no interim hypotheses.
                }

                @Override
                public void onFinal(String fullText) {
                    finalText.set(fullText);
                    done.countDown();
                }

                @Override
                public void onError(String message) {
                    error.set(message);
                    done.countDown();
                }
            });
            provider.sendAudio(new byte[] {0, 1, 2, 3});
            provider.sendAudio(new byte[] {4, 5, 6, 7});
            provider.finish();

            assertTrue(done.await(5, TimeUnit.SECONDS), "no terminal event");
            assertNull(error.get());
            assertEquals("你好，世界", finalText.get());

            JsonNode body = MAPPER.readTree(requestBody.get());
            assertEquals("mimo-v2.5-asr", body.path("model").asString(""));
            assertEquals("zh", body.path("asr_options").path("language").asString(""));
            String data = body.path("messages").path(0).path("content").path(0)
                    .path("input_audio").path("data").asString("");
            assertTrue(data.startsWith("data:audio/wav;base64,"), "input_audio must be a wav data URL");
            byte[] wav = Base64.getDecoder().decode(data.substring("data:audio/wav;base64,".length()));
            assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7}, Arrays.copyOfRange(wav, 44, wav.length));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpErrorSurfacesAsOnError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = "{\"error\":{\"message\":\"bad key\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            MimoAsrProvider provider = new MimoAsrProvider(MAPPER, "bad", baseUrl, null, "zh");
            AtomicReference<String> error = new AtomicReference<>();
            AtomicReference<String> finalText = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            provider.start(new StreamingAsrProvider.Listener() {
                @Override
                public void onPartial(String fullText) {
                }

                @Override
                public void onFinal(String fullText) {
                    finalText.set(fullText);
                    done.countDown();
                }

                @Override
                public void onError(String message) {
                    error.set(message);
                    done.countDown();
                }
            });
            provider.finish();

            assertTrue(done.await(5, TimeUnit.SECONDS), "no terminal event");
            assertNull(finalText.get());
            assertTrue(error.get().contains("401"), "error should carry the HTTP status: " + error.get());
        } finally {
            server.stop(0);
        }
    }
}
