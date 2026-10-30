package dev.kualu.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedHeader = new AtomicReference<>();
    private final AtomicReference<String> capturedMethod = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedHeader.set(exchange.getRequestHeaders().getFirst("X-Custom"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void get_sendsUrlAndHeaders() {
        HttpClient client = new HttpClient();

        HttpResponse<String> response = client.get(baseUrl + "/echo", Map.of("X-Custom", "get-value"));

        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        assertEquals("GET", capturedMethod.get());
        assertEquals("get-value", capturedHeader.get());
        assertEquals("", capturedBody.get());
    }

    @Test
    void post_sendsUrlHeadersAndBody() {
        HttpClient client = new HttpClient();

        HttpResponse<String> response = client.post(
                baseUrl + "/echo",
                Map.of("X-Custom", "post-value", "Content-Type", "application/json"),
                "{\"name\":\"alice\"}");

        assertEquals(200, response.statusCode());
        assertEquals("POST", capturedMethod.get());
        assertEquals("post-value", capturedHeader.get());
        assertEquals("{\"name\":\"alice\"}", capturedBody.get());
    }

    @Test
    void send_supportsPutWithBody() {
        HttpClient client = new HttpClient();

        HttpResponse<String> response = client.send("PUT", baseUrl + "/echo",
                Map.of("X-Custom", "put-value"), "payload");

        assertEquals(200, response.statusCode());
        assertEquals("PUT", capturedMethod.get());
        assertEquals("put-value", capturedHeader.get());
        assertEquals("payload", capturedBody.get());
    }

    @Test
    void send_nullHeadersAndBody_areAllowed() {
        HttpClient client = new HttpClient();

        HttpResponse<String> response = client.send("GET", baseUrl + "/echo", null, null);

        assertEquals(200, response.statusCode());
        assertNotNull(response.body());
    }
}
