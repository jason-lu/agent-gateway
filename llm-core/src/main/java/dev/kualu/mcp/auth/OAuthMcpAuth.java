package dev.kualu.mcp.auth;

import dev.kualu.http.HttpClient;
import dev.kualu.mcp.McpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * OAuth credentials for a remote MCP server: attaches a bearer token to every
 * request and, on {@code 401}/{@code 403}, refreshes it or runs an interactive
 * login (authorization code + PKCE in the system browser, device grant as
 * fallback) before the caller retries.
 */
public final class OAuthMcpAuth implements McpAuth {

    private static final Logger log = LoggerFactory.getLogger(OAuthMcpAuth.class);

    private final String serverName;
    private final String resourceUrl;
    private final McpTokenStore store;
    private final OAuthConfig config;
    private final HttpClient http;
    private final Object loginLock = new Object();

    public OAuthMcpAuth(String serverName, String resourceUrl) {
        this(serverName, resourceUrl, FileTokenStore.defaultStore(), OAuthConfig.DEFAULT);
    }

    public OAuthMcpAuth(String serverName, String resourceUrl, McpTokenStore store, OAuthConfig config) {
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.resourceUrl = Objects.requireNonNull(resourceUrl, "resourceUrl");
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.http = new HttpClient();
    }

    @Override
    public void apply(Map<String, String> headers) {
        store.load(serverName).ifPresent(tokens ->
                headers.put("Authorization", "Bearer " + tokens.accessToken()));
    }

    @Override
    public boolean onUnauthorized(int status, Map<String, String> responseHeaders) {
        synchronized (loginLock) {
            Optional<StoredTokens> current = store.load(serverName);
            if (current.isPresent() && current.get().refreshToken() != null) {
                try {
                    OAuthFlow.Endpoints endpoints = OAuthFlow.discover(resourceUrl, http);
                    StoredTokens refreshed = OAuthFlow.refresh(
                            http, endpoints.tokenEndpoint(), clientId(endpoints), current.get().refreshToken());
                    store.save(serverName, mergeRefresh(current.get(), refreshed));
                    log.info("mcp oauth token refreshed for {}", serverName);
                    return true;
                } catch (RuntimeException ex) {
                    log.warn("mcp oauth refresh failed for {}: {}; falling back to interactive login",
                            serverName, ex.getMessage());
                }
            }
            try {
                OAuthFlow.Endpoints endpoints = OAuthFlow.discover(resourceUrl, http);
                String clientId = clientId(endpoints);
                StoredTokens fresh;
                try {
                    fresh = OAuthFlow.authorizationCodePkce(
                            http, endpoints, clientId, config.scopes(), config.launcher());
                } catch (RuntimeException browserFailure) {
                    if (!config.allowDeviceCode()) {
                        throw browserFailure;
                    }
                    log.info("browser login failed ({}); trying device grant", browserFailure.getMessage());
                    fresh = OAuthFlow.deviceCode(http, endpoints, clientId, config.scopes(),
                            message -> log.info("[mcp-oauth] {}", message));
                }
                store.save(serverName, fresh);
                log.info("mcp oauth login completed for {}", serverName);
                return true;
            } catch (RuntimeException ex) {
                log.error("mcp oauth login failed for {}: {}", serverName, ex.getMessage());
                return false;
            }
        }
    }

    /** Refresh responses may omit the refresh token; keep the old one then. */
    private static StoredTokens mergeRefresh(StoredTokens current, StoredTokens refreshed) {
        return refreshed.refreshToken() != null
                ? refreshed
                : new StoredTokens(refreshed.accessToken(), current.refreshToken(), refreshed.expiresAt());
    }

    private String clientId(OAuthFlow.Endpoints endpoints) {
        if (config.clientId() != null && !config.clientId().isBlank()) {
            return config.clientId();
        }
        if (endpoints.registrationEndpoint() == null) {
            throw new McpException("no client id configured and the server has no registration_endpoint");
        }
        return OAuthFlow.registerClient(http, endpoints.registrationEndpoint(),
                java.util.List.of("http://127.0.0.1/callback"));
    }
}
