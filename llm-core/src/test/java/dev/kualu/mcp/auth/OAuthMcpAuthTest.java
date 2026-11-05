package dev.kualu.mcp.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthMcpAuthTest {

    @Test
    void applyAddsBearerFromStore() {
        InMemoryTokenStore store = new InMemoryTokenStore();
        store.save("demo", new StoredTokens("tok-1", "ref", Long.MAX_VALUE));
        OAuthMcpAuth auth = new OAuthMcpAuth("demo", "https://example.com/mcp", store, OAuthConfig.DEFAULT);

        Map<String, String> headers = new HashMap<>();
        auth.apply(headers);
        assertEquals("Bearer tok-1", headers.get("Authorization"));
    }

    @Test
    void applyWithoutTokensSendsNothing() {
        OAuthMcpAuth auth = new OAuthMcpAuth(
                "demo", "https://example.com/mcp", new InMemoryTokenStore(), OAuthConfig.DEFAULT);
        Map<String, String> headers = new HashMap<>();
        auth.apply(headers);
        assertNull(headers.get("Authorization"));
    }

    @Test
    void unauthorizedRefreshesTheToken() throws Exception {
        try (FakeOAuthServer as = new FakeOAuthServer()) {
            InMemoryTokenStore store = new InMemoryTokenStore();
            store.save("demo", new StoredTokens("stale", "refresh-1", Long.MAX_VALUE));
            OAuthMcpAuth auth = new OAuthMcpAuth(
                    "demo", as.resourceUrl(), store,
                    OAuthConfig.DEFAULT.withClientId("cid"));

            assertTrue(auth.onUnauthorized(401, Map.of()));
            assertEquals("access-refreshed", store.load("demo").orElseThrow().accessToken());
        }
    }

    @Test
    void unauthorizedWithoutRefreshRunsInteractiveLogin() throws Exception {
        try (FakeOAuthServer as = new FakeOAuthServer()) {
            InMemoryTokenStore store = new InMemoryTokenStore();
            OAuthConfig config = OAuthConfig.DEFAULT
                    .withClientId("cid")
                    .withBrowserLauncher(uri -> simulateBrowser(uri));
            OAuthMcpAuth auth = new OAuthMcpAuth("demo", as.resourceUrl(), store, config);

            assertTrue(auth.onUnauthorized(401, Map.of()));
            assertEquals("access-from-code", store.load("demo").orElseThrow().accessToken());
            assertEquals("refresh-1", store.load("demo").orElseThrow().refreshToken());
        }
    }

    @Test
    void failedLoginReturnsFalse() {
        OAuthConfig config = OAuthConfig.DEFAULT
                .withClientId("cid")
                .withBrowserLauncher(uri -> {
                    throw new IllegalStateException("no browser here");
                });
        OAuthMcpAuth auth = new OAuthMcpAuth(
                "demo", "http://127.0.0.1:1/mcp", new InMemoryTokenStore(), config);
        assertFalse(auth.onUnauthorized(401, Map.of()));
    }

    /** Drives the loopback callback like a user's browser completing the login. */
    private static void simulateBrowser(URI authorizeUri) {
        try {
            Map<String, String> params = new java.util.LinkedHashMap<>();
            for (String pair : authorizeUri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                params.put(java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
            java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(URI.create(
                                    params.get("redirect_uri") + "?code=test-code&state=" + params.get("state")))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
