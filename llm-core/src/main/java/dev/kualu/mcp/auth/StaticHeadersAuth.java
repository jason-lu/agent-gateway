package dev.kualu.mcp.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Static credential headers (PAT / bearer token), sent as configured. */
public final class StaticHeadersAuth implements McpAuth {

    private final Map<String, String> headers;

    public StaticHeadersAuth(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        this.headers = Map.copyOf(new LinkedHashMap<>(headers));
    }

    @Override
    public void apply(Map<String, String> target) {
        target.putAll(headers);
    }
}
