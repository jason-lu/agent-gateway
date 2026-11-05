package dev.kualu.mcp.auth;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Options for the OAuth MCP login flow.
 *
 * @param clientId        pre-registered client id (e.g. own GitHub App); {@code null}
 *                        triggers dynamic client registration
 * @param scopes          OAuth scopes to request
 * @param allowDeviceCode fall back to the device grant when no browser is available
 * @param browserLauncher opens the authorize URL (tests substitute a stub)
 */
public record OAuthConfig(
        String clientId,
        List<String> scopes,
        boolean allowDeviceCode,
        Consumer<URI> browserLauncher) {

    public static final OAuthConfig DEFAULT = new OAuthConfig(null, List.of(), true, OAuthConfig::openBrowser);

    public OAuthConfig {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        browserLauncher = browserLauncher == null ? OAuthConfig::openBrowser : browserLauncher;
    }

    public OAuthConfig withClientId(String id) {
        return new OAuthConfig(id, scopes, allowDeviceCode, browserLauncher);
    }

    public OAuthConfig withScopes(List<String> requested) {
        return new OAuthConfig(clientId, requested, allowDeviceCode, browserLauncher);
    }

    public OAuthConfig withBrowserLauncher(Consumer<URI> launcher) {
        return new OAuthConfig(clientId, scopes, allowDeviceCode, launcher);
    }

    /**
     * System browser on the PC. Uses the OS launcher directly so the library
     * never needs {@code java.desktop}; falls back to printing the URL.
     */
    static void openBrowser(URI uri) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", uri.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", uri.toString()).start();
            }
        } catch (IOException ex) {
            System.err.println("Open this URL to authorize: " + uri);
        }
    }

    /** Non-null browser launcher, for callers that must not depend on AWT. */
    Consumer<URI> launcher() {
        return Objects.requireNonNull(browserLauncher, "browserLauncher");
    }
}
