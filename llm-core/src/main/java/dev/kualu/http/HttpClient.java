package dev.kualu.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Thin HTTP client that accepts a URL, headers, and an optional body.
 * When {@code AGENT_HTTP_PROXY} is set (e.g. {@code 127.0.0.1:7890}), all
 * outbound requests go through that HTTP proxy.
 */
public class HttpClient {

    private final java.net.http.HttpClient client;
    private final Duration timeout;

    public HttpClient() {
        this(Duration.ofSeconds(30));
    }

    public HttpClient(Duration timeout) {
        this(timeout, envProxySelector());
    }

    /** @param proxy {@code null} for a direct connection */
    public HttpClient(Duration timeout, ProxySelector proxy) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL);
        if (proxy != null) {
            builder.proxy(proxy);
        }
        this.client = builder.build();
    }

    /** Reads {@code AGENT_HTTP_PROXY}; {@code null} when unset. */
    static ProxySelector envProxySelector() {
        String spec = System.getenv("AGENT_HTTP_PROXY");
        return (spec == null || spec.isBlank()) ? null : parseProxy(spec);
    }

    /** Accepts {@code host:port} or {@code scheme://host:port}. */
    static ProxySelector parseProxy(String spec) {
        String value = spec.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        if (value.isBlank() || value.startsWith(":") || value.endsWith(":")) {
            throw new IllegalArgumentException(
                    "AGENT_HTTP_PROXY must look like host:port (got '" + spec + "')");
        }
        String host;
        int port;
        try {
            int colon = value.lastIndexOf(':');
            if (colon > 0) {
                host = value.substring(0, colon);
                port = Integer.parseInt(value.substring(colon + 1));
            } else {
                host = value;
                port = 80;
            }
            if (host.isBlank() || port < 1 || port > 65535) {
                throw new IllegalArgumentException("bad host/port");
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "AGENT_HTTP_PROXY must look like host:port (got '" + spec + "')", ex);
        }
        Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // failures surface on the request itself
            }
        };
    }

    public HttpResponse<String> get(String url, Map<String, String> headers) {
        return send("GET", url, headers, null);
    }

    public HttpResponse<String> post(String url, Map<String, String> headers, String body) {
        return send("POST", url, headers, body);
    }

    public HttpResponse<String> put(String url, Map<String, String> headers, String body) {
        return send("PUT", url, headers, body);
    }

    public HttpResponse<String> patch(String url, Map<String, String> headers, String body) {
        return send("PATCH", url, headers, body);
    }

    public HttpResponse<String> delete(String url, Map<String, String> headers, String body) {
        return send("DELETE", url, headers, body);
    }

    /**
     * Sends an HTTP request with the given method, URL, headers, and body.
     * Body may be {@code null} for requests without a payload.
     */
    public HttpResponse<String> send(String method, String url, Map<String, String> headers, String body) {
        HttpRequest request = buildRequest(method, url, headers, body, true);
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new HttpClientException(
                    "HTTP " + method.toUpperCase() + " " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpClientException("HTTP " + method.toUpperCase() + " " + url + " interrupted", e);
        }
    }

    /**
     * Sends an HTTP request and returns the response body as a stream of lines.
     * The caller must close the stream to release the connection.
     * Unlike {@link #send}, this does not apply a request timeout so long-lived
     * streams (e.g. SSE) are not cut off mid-flight.
     */
    public HttpResponse<Stream<String>> sendLines(
            String method, String url, Map<String, String> headers, String body) {
        HttpRequest request = buildRequest(method, url, headers, body, false);
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new HttpClientException(
                    "HTTP " + method.toUpperCase() + " " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpClientException("HTTP " + method.toUpperCase() + " " + url + " interrupted", e);
        }
    }

    /** Streaming POST; see {@link #sendLines}. */
    public HttpResponse<Stream<String>> postLines(String url, Map<String, String> headers, String body) {
        return sendLines("POST", url, headers, body);
    }

    private HttpRequest buildRequest(
            String method, String url, Map<String, String> headers, String body, boolean applyTimeout) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");

        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url));
        if (applyTimeout) {
            builder.timeout(timeout);
        }
        if (headers != null) {
            headers.forEach(builder::header);
        }

        HttpRequest.BodyPublisher publisher = (body == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        builder.method(method.toUpperCase(), publisher);
        return builder.build();
    }
}
