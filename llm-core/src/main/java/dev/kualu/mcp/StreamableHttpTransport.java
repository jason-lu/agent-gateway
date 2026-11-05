package dev.kualu.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kualu.http.HttpClient;
import dev.kualu.mcp.auth.McpAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * MCP transport over Streamable HTTP: each JSON-RPC message is POSTed to the
 * endpoint; the response is either a single JSON message or an SSE stream of
 * messages ({@code 202}/{@code 204} acknowledges notifications). The
 * {@code Mcp-Session-Id} assigned by the server is attached to later requests;
 * a {@code 404} with a session id raises {@link McpSessionExpiredException}.
 */
public final class StreamableHttpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpTransport.class);

    private final String url;
    private final McpAuth auth;
    private final HttpClient http;

    private volatile Consumer<JsonObject> onMessage;
    private volatile String sessionId;
    private volatile boolean closed;

    public StreamableHttpTransport(String url, McpAuth auth) {
        this(url, auth, Duration.ofSeconds(120));
    }

    public StreamableHttpTransport(String url, McpAuth auth, Duration requestTimeout) {
        this.url = requireTrustedUrl(Objects.requireNonNull(url, "url"));
        this.auth = Objects.requireNonNull(auth, "auth");
        this.http = new HttpClient(Objects.requireNonNull(requestTimeout, "requestTimeout"));
    }

    /**
     * Remote servers must use HTTPS so credentials never cross the network in
     * plaintext; loopback is the single exception (local development).
     */
    static String requireTrustedUrl(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return url;
        }
        if ("http".equals(scheme) && isLoopback(uri.getHost())) {
            return url;
        }
        throw new IllegalArgumentException("mcp url must be https (loopback excepted): " + url);
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1")
                || normalized.equals("[::1]");
    }

    @Override
    public void start(Consumer<JsonObject> onMessage, Runnable onClosed) {
        this.onMessage = Objects.requireNonNull(onMessage, "onMessage");
    }

    @Override
    public void send(JsonObject message) {
        if (closed) {
            throw new McpException("mcp http transport is closed");
        }
        HttpResponse<String> response = post(message.toString());
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            if (auth.onUnauthorized(status, Map.of())) {
                response = post(message.toString());
                status = response.statusCode();
            }
        }
        captureSessionId(response);
        if (status == 202 || status == 204) {
            return;
        }
        if (status == 404 && sessionId != null) {
            throw new McpSessionExpiredException("mcp session expired (404): " + sessionId);
        }
        if (status < 200 || status >= 300) {
            throw new McpException("mcp http " + status + ": " + snippet(response.body()));
        }
        deliverBody(response);
    }

    private HttpResponse<String> post(String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            headers.put("Mcp-Session-Id", sessionId);
        }
        auth.apply(headers);
        return http.post(url, headers, body);
    }

    private void captureSessionId(HttpResponse<String> response) {
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> {
            if (!value.isBlank()) {
                sessionId = value;
            }
        });
    }

    private void deliverBody(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return;
        }
        String contentType = response.headers()
                .firstValue("Content-Type")
                .map(value -> value.toLowerCase(Locale.ROOT))
                .orElse("");
        if (contentType.contains("text/event-stream")) {
            deliverSse(body);
        } else {
            deliverMessage(body);
        }
    }

    private void deliverSse(String body) {
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                if (data.length() > 0) {
                    deliverMessage(data.toString());
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring("data:".length()).stripLeading();
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
            }
            // event:/id:/retry: lines and comments are irrelevant for JSON-RPC delivery
        }
        if (data.length() > 0) {
            deliverMessage(data.toString());
        }
    }

    private void deliverMessage(String raw) {
        try {
            if (onMessage != null) {
                onMessage.accept(JsonParser.parseString(raw).getAsJsonObject());
            }
        } catch (RuntimeException ex) {
            log.warn("skipping unparseable mcp message: {}", ex.getMessage());
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
