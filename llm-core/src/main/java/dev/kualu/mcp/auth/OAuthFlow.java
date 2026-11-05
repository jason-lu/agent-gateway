package dev.kualu.mcp.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kualu.http.HttpClient;
import dev.kualu.mcp.McpException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;

/**
 * OAuth 2.0 flows for MCP remote servers: metadata discovery (RFC 9728 /
 * RFC 8414), dynamic client registration (RFC 7591), authorization code + PKCE
 * with a loopback redirect (RFC 8252), device authorization (RFC 8628), and
 * refresh. The flows use the project {@link HttpClient}; PKCE is the standard
 * S256 construction (SHA-256 over the base64url verifier).
 */
final class OAuthFlow {

    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final long LOGIN_TIMEOUT_SECONDS = 300;

    record Endpoints(
            URI authorizationEndpoint,
            URI tokenEndpoint,
            URI registrationEndpoint,
            URI deviceAuthorizationEndpoint,
            List<String> codeChallengeMethods) {
    }

    private OAuthFlow() {
    }

    /** Resolves AS endpoints for the protected resource. */
    static Endpoints discover(String resourceUrl, HttpClient http) {
        URI resource = URI.create(resourceUrl);
        String origin = resource.getScheme() + "://" + resource.getAuthority();
        JsonObject resourceMetadata = firstJson(http, List.of(
                origin + "/.well-known/oauth-protected-resource",
                origin + "/.well-known/oauth-protected-resource" + resource.getPath()));
        String issuer = null;
        if (resourceMetadata != null && resourceMetadata.has("authorization_servers")
                && resourceMetadata.get("authorization_servers").isJsonArray()
                && resourceMetadata.getAsJsonArray("authorization_servers").size() > 0) {
            issuer = resourceMetadata.getAsJsonArray("authorization_servers").get(0).getAsString();
        }
        if (issuer == null) {
            issuer = origin;
        }
        String issuerTrimmed = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        JsonObject asMetadata = firstJson(http, asMetadataCandidates(issuerTrimmed));
        if (asMetadata == null) {
            throw new McpException("cannot discover OAuth metadata for " + resourceUrl);
        }
        List<String> methods = new ArrayList<>();
        if (asMetadata.has("code_challenge_methods_supported")
                && asMetadata.get("code_challenge_methods_supported").isJsonArray()) {
            asMetadata.getAsJsonArray("code_challenge_methods_supported")
                    .forEach(element -> methods.add(element.getAsString()));
        }
        return new Endpoints(
                uri(asMetadata, "authorization_endpoint"),
                uri(asMetadata, "token_endpoint"),
                asMetadata.has("registration_endpoint") ? URI.create(asMetadata.get("registration_endpoint").getAsString()) : null,
                asMetadata.has("device_authorization_endpoint")
                        ? URI.create(asMetadata.get("device_authorization_endpoint").getAsString()) : null,
                List.copyOf(methods));
    }

    /**
     * Metadata locations for an issuer: the path-suffix form, the RFC 8414
     * path-insertion form (needed for issuers like {@code https://github.com/login/oauth}),
     * and the origin-root form — times the RFC 8414 and OIDC discovery names.
     */
    static List<String> asMetadataCandidates(String issuer) {
        URI uri = URI.create(issuer);
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        String path = uri.getPath() == null ? "" : uri.getPath();
        List<String> candidates = new ArrayList<>();
        for (String name : List.of("oauth-authorization-server", "openid-configuration")) {
            candidates.add(issuer + "/.well-known/" + name);
            if (!path.isBlank()) {
                candidates.add(origin + "/.well-known/" + name + path);
            }
            candidates.add(origin + "/.well-known/" + name);
        }
        return List.copyOf(candidates);
    }

    private static JsonObject firstJson(HttpClient http, List<String> urls) {
        for (String url : urls) {
            try {
                HttpResponse<String> response = http.get(url, Map.of("Accept", "application/json"));
                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && response.body() != null && !response.body().isBlank()) {
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                }
            } catch (RuntimeException ex) {
                // try the next candidate
            }
        }
        return null;
    }

    private static URI uri(JsonObject metadata, String field) {
        if (!metadata.has(field) || !metadata.get(field).isJsonPrimitive()) {
            throw new McpException("OAuth metadata has no '" + field + "'");
        }
        return URI.create(metadata.get(field).getAsString());
    }

    /** Dynamic client registration for a public native client; returns the client id. */
    static String registerClient(HttpClient http, URI registrationEndpoint, List<String> redirectUris) {
        JsonObject body = new JsonObject();
        body.addProperty("client_name", "dev.kualu.agent");
        body.addProperty("application_type", "native");
        body.addProperty("token_endpoint_auth_method", "none");
        com.google.gson.JsonArray redirects = new com.google.gson.JsonArray();
        redirectUris.forEach(redirects::add);
        body.add("redirect_uris", redirects);
        com.google.gson.JsonArray grants = new com.google.gson.JsonArray();
        grants.add("authorization_code");
        grants.add("refresh_token");
        body.add("grant_types", grants);
        com.google.gson.JsonArray responseTypes = new com.google.gson.JsonArray();
        responseTypes.add("code");
        body.add("response_types", responseTypes);

        HttpResponse<String> response = http.post(registrationEndpoint.toString(),
                Map.of("Content-Type", "application/json"), body.toString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpException("OAuth client registration failed: HTTP " + response.statusCode());
        }
        JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!result.has("client_id")) {
            throw new McpException("OAuth client registration returned no client_id");
        }
        return result.get("client_id").getAsString();
    }

    /**
     * Authorization code + PKCE through the system browser with a loopback
     * redirect. Returns the issued tokens.
     */
    static StoredTokens authorizationCodePkce(
            HttpClient http, Endpoints endpoints, String clientId, List<String> scopes, Consumer<URI> browser) {
        String verifier = randomToken();
        String challenge = s256Challenge(verifier);
        String state = randomToken();

        AtomicReference<String> code = new AtomicReference<>();
        AtomicReference<String> expectedState = new AtomicReference<>(state);
        CountDownLatch done = new CountDownLatch(1);
        HttpServer callbackServer = startCallbackServer(code, expectedState, done);
        try {
            int port = callbackServer.getAddress().getPort();
            String redirectUri = "http://127.0.0.1:" + port + "/callback";
            URI authorizeUri = authorizeUri(endpoints, clientId, redirectUri, state, challenge, scopes);
            browser.accept(authorizeUri);

            if (!done.await(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new McpException("timed out waiting for OAuth browser login");
            }
            if (code.get() == null) {
                throw new McpException("OAuth authorization failed: no code returned (state mismatch or denied)");
            }
            return exchangeCode(http, endpoints.tokenEndpoint(), clientId, redirectUri,
                    code.get(), verifier);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpException("OAuth login interrupted", ex);
        } finally {
            callbackServer.stop(0);
        }
    }

    private static URI authorizeUri(
            Endpoints endpoints, String clientId, String redirectUri,
            String state, String challenge, List<String> scopes) {
        if (!endpoints.codeChallengeMethods().isEmpty()
                && !endpoints.codeChallengeMethods().contains("S256")) {
            throw new McpException("OAuth server does not support PKCE S256");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("state", state);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        if (!scopes.isEmpty()) {
            params.put("scope", String.join(" ", scopes));
        }
        String endpoint = endpoints.authorizationEndpoint().toString();
        String separator = endpoint.contains("?") ? "&" : "?";
        return URI.create(endpoint + separator + form(params));
    }

    /** Base64url of 32 random bytes — the PKCE verifier / OAuth state material. */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** PKCE S256: BASE64URL(SHA256(ASCII(verifier))). */
    static String s256Challenge(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static HttpServer startCallbackServer(
            AtomicReference<String> code, AtomicReference<String> state, CountDownLatch done) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/callback", exchange -> {
                Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
                String returnedState = params.get("state");
                if (state.get() != null && !state.get().equals(returnedState)) {
                    respond(exchange, 400, "State mismatch — close this window and retry.");
                    done.countDown();
                    return;
                }
                if (params.containsKey("code")) {
                    code.set(params.get("code"));
                    respond(exchange, 200, "Login complete — you can close this window.");
                } else {
                    respond(exchange, 400, "Authorization failed: "
                            + params.getOrDefault("error", "no code returned"));
                }
                done.countDown();
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new McpException("cannot start loopback callback server: " + ex.getMessage(), ex);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String message)
            throws IOException {
        byte[] body = ("<html><body><p>" + message + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    /** Device authorization grant: prints the user code and polls for consent. */
    static StoredTokens deviceCode(
            HttpClient http, Endpoints endpoints, String clientId, List<String> scopes, Consumer<String> console) {
        if (endpoints.deviceAuthorizationEndpoint() == null) {
            throw new McpException("OAuth server has no device_authorization_endpoint");
        }
        HttpResponse<String> response = http.post(endpoints.deviceAuthorizationEndpoint().toString(),
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                form(Map.of("client_id", clientId, "scope", String.join(" ", scopes))));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpException("device authorization request failed: HTTP " + response.statusCode());
        }
        JsonObject device = JsonParser.parseString(response.body()).getAsJsonObject();
        String deviceCode = device.get("device_code").getAsString();
        String userCode = device.get("user_code").getAsString();
        String verificationUri = device.has("verification_uri")
                ? device.get("verification_uri").getAsString()
                : device.get("verification_url").getAsString();
        long interval = device.has("interval") ? device.get("interval").getAsLong() : 5;
        long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_SECONDS * 1000;

        console.accept("To authorize, open " + verificationUri + " and enter the code: " + userCode);
        while (System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.SECONDS.sleep(Math.max(1, interval));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new McpException("device authorization interrupted", ex);
            }
            HttpResponse<String> poll = http.post(endpoints.tokenEndpoint().toString(),
                    Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    form(Map.of(
                            "grant_type", DEVICE_GRANT,
                            "device_code", deviceCode,
                            "client_id", clientId)));
            JsonObject body = parseJsonObject(poll.body());
            if (poll.statusCode() >= 200 && poll.statusCode() < 300) {
                return tokensFrom(body);
            }
            String error = body.has("error") ? body.get("error").getAsString() : "";
            if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
                if ("slow_down".equals(error)) {
                    interval += 5;
                }
                continue;
            }
            throw new McpException("device authorization failed: " + error);
        }
        throw new McpException("timed out waiting for device authorization");
    }

    static StoredTokens exchangeCode(
            HttpClient http, URI tokenEndpoint, String clientId, String redirectUri,
            String code, String codeVerifier) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", redirectUri);
        params.put("client_id", clientId);
        params.put("code_verifier", codeVerifier);
        return tokenRequest(http, tokenEndpoint, params);
    }

    static StoredTokens refresh(HttpClient http, URI tokenEndpoint, String clientId, String refreshToken) {
        return tokenRequest(http, tokenEndpoint, Map.of(
                "grant_type", "refresh_token",
                "refresh_token", refreshToken,
                "client_id", clientId));
    }

    private static StoredTokens tokenRequest(HttpClient http, URI tokenEndpoint, Map<String, String> params) {
        HttpResponse<String> response = http.post(tokenEndpoint.toString(),
                Map.of("Content-Type", "application/x-www-form-urlencoded"), form(params));
        JsonObject body = parseJsonObject(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error = body.has("error") ? body.get("error").getAsString() : ("HTTP " + response.statusCode());
            throw new McpException("OAuth token request failed: " + error);
        }
        return tokensFrom(body);
    }

    private static StoredTokens tokensFrom(JsonObject body) {
        if (!body.has("access_token")) {
            throw new McpException("OAuth token response has no access_token");
        }
        long expiresAt = body.has("expires_in")
                ? System.currentTimeMillis() / 1000 + body.get("expires_in").getAsLong()
                : Long.MAX_VALUE;
        return new StoredTokens(
                body.get("access_token").getAsString(),
                body.has("refresh_token") ? body.get("refresh_token").getAsString() : null,
                expiresAt);
    }

    private static JsonObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            body.append('=');
            body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return body.toString();
    }
}
