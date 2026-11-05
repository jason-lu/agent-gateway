package dev.kualu.mcp.auth;

import dev.kualu.http.HttpClient;
import dev.kualu.mcp.McpException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthFlowTest {

    private final HttpClient http = new HttpClient(Duration.ofSeconds(5));

    /** RFC 7636 appendix B published vector. */
    @Test
    void pkceS256MatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthFlow.s256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void metadataCandidatesCoverRfc8414PathInsertion() {
        List<String> candidates = OAuthFlow.asMetadataCandidates("https://github.com/login/oauth");
        assertTrue(candidates.contains("https://github.com/.well-known/oauth-authorization-server/login/oauth"),
                "RFC 8414 insertion form must be probed for path issuers");
        assertTrue(candidates.contains("https://github.com/.well-known/oauth-authorization-server"));
        assertTrue(candidates.contains("https://github.com/login/oauth/.well-known/openid-configuration"));
    }

    @Test
    void discoversEndpointsFromResourceMetadata() throws Exception {
        try (FakeOAuthServer as = new FakeOAuthServer()) {
            OAuthFlow.Endpoints endpoints = OAuthFlow.discover(as.resourceUrl(), http);
            assertEquals(as.origin() + "/authorize", endpoints.authorizationEndpoint().toString());
            assertEquals(as.origin() + "/token", endpoints.tokenEndpoint().toString());
            assertNotNull(endpoints.registrationEndpoint());
            assertNotNull(endpoints.deviceAuthorizationEndpoint());
            assertTrue(endpoints.codeChallengeMethods().contains("S256"));
        }
    }

    @Test
    void registersClientDynamically() throws Exception {
        try (FakeOAuthServer as = new FakeOAuthServer()) {
            String clientId = OAuthFlow.registerClient(
                    http, URI.create(as.origin() + "/register"), List.of("http://127.0.0.1/callback"));
            assertEquals("dyn-client", clientId);
        }
    }

    @Test
    void exchangesCodeAndRefreshes() throws Exception {
        try (FakeOAuthServer as = new FakeOAuthServer()) {
            StoredTokens tokens = OAuthFlow.exchangeCode(
                    http, URI.create(as.origin() + "/token"), "cid",
                    "http://127.0.0.1:1234/callback", "the-code", "the-verifier");
            assertEquals("access-from-code", tokens.accessToken());
            assertEquals("refresh-1", tokens.refreshToken());

            StoredTokens refreshed = OAuthFlow.refresh(
                    http, URI.create(as.origin() + "/token"), "cid", "refresh-1");
            assertEquals("access-refreshed", refreshed.accessToken());
        }
    }

    @Test
    void deviceCodeFlowPollsUntilApproved() throws Exception {
        try (FakeOAuthServer as = new FakeOAuthServer()) {
            as.devicePendingFirst = 1;
            AtomicReference<String> shown = new AtomicReference<>();
            StoredTokens tokens = OAuthFlow.deviceCode(
                    http, OAuthFlow.discover(as.resourceUrl(), http), "cid", List.of(),
                    shown::set);
            assertEquals("access-device", tokens.accessToken());
            assertTrue(shown.get().contains("ABCD-1234"));
            assertTrue(as.devicePolls.get() >= 2);
        }
    }

    @Test
    void authorizationCodePkceCompletesThroughLoopback() throws Exception {
        try (FakeOAuthServer as = new FakeOAuthServer()) {
            OAuthFlow.Endpoints endpoints = OAuthFlow.discover(as.resourceUrl(), http);
            // Stand-in for the user's browser: read state/redirect from the authorize
            // URL and hit the loopback callback as the AS would.
            java.util.function.Consumer<URI> browser = authorizeUri -> {
                try {
                    Map<String, String> params = query(authorizeUri.getRawQuery());
                    String redirect = params.get("redirect_uri");
                    assertNotNull(params.get("code_challenge"));
                    assertEquals("S256", params.get("code_challenge_method"));
                    HttpResponse<String> hit = java.net.http.HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(
                                            redirect + "?code=test-code&state=" + params.get("state")))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, hit.statusCode());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            };

            StoredTokens tokens = OAuthFlow.authorizationCodePkce(
                    http, endpoints, "cid", List.of("repo"), browser);
            assertEquals("access-from-code", tokens.accessToken());
            assertEquals("refresh-1", tokens.refreshToken());
        }
    }

    @Test
    void stateMismatchRejectsTheCallback() throws Exception {
        try (FakeOAuthServer as = new FakeOAuthServer()) {
            OAuthFlow.Endpoints endpoints = OAuthFlow.discover(as.resourceUrl(), http);
            java.util.function.Consumer<URI> browser = authorizeUri -> {
                try {
                    Map<String, String> params = query(authorizeUri.getRawQuery());
                    HttpResponse<String> hit = java.net.http.HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(
                                            params.get("redirect_uri") + "?code=x&state=WRONG"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(400, hit.statusCode());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            };
            assertThrows(McpException.class, () -> OAuthFlow.authorizationCodePkce(
                    http, endpoints, "cid", List.of(), browser));
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
