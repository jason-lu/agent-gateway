package dev.kualu.web.auth;

import dev.kualu.web.config.SecurityProperties;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interactive login sessions built on refresh tokens:
 * <ul>
 *   <li>the access JWT carries a {@code sid} claim naming its session row;</li>
 *   <li>every authenticated request touches {@code last_used_at} (that is "activity");</li>
 *   <li>a session with no activity for {@code agent.security.refresh-idle-timeout}
 *       is deleted and its access tokens stop working — the session expires on
 *       idle, not on a fixed wall-clock TTL;</li>
 *   <li>each {@link #refresh} rotates the stored hash, so a stolen raw refresh
 *       token dies with the next legitimate use.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenStore store;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final Duration idleTimeout;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenStore store,
            JwtService jwtService,
            UserDetailsService userDetailsService,
            SecurityProperties properties) {
        this.store = store;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.idleTimeout = properties.refreshIdleTimeout();
    }

    public record Issued(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    /** Creates a fresh session row and returns its token pair. */
    public Issued issue(String username, List<String> roles) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String raw = newRawToken();
        store.insert(new RefreshTokenStore.Entry(id, username, now, now), hash(raw));
        return new Issued(jwtService.issueToken(username, roles, id), raw, jwtService.expiresInSeconds());
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the stored hash but
     * keeping the same session id so in-flight access tokens stay valid.
     * Empty when the token is unknown or the session has gone idle.
     */
    public Optional<Issued> refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }
        Optional<RefreshTokenStore.Entry> found = store.findByHash(hash(rawRefreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshTokenStore.Entry entry = found.get();
        Instant now = Instant.now();
        if (isIdle(entry.lastUsedAt(), now)) {
            store.deleteById(entry.id());
            return Optional.empty();
        }
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(entry.owner());
        } catch (Exception ex) {
            store.deleteById(entry.id());
            return Optional.empty();
        }
        String raw = newRawToken();
        store.rotate(entry.id(), hash(raw), now);
        return Optional.of(new Issued(
                jwtService.issueToken(user.getUsername(), rolesOf(user), entry.id()),
                raw,
                jwtService.expiresInSeconds()));
    }

    /** Revokes the session behind a refresh token. Unknown tokens are ignored. */
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        store.findByHash(hash(rawRefreshToken)).ifPresent(entry -> store.deleteById(entry.id()));
    }

    /**
     * Gate for the JWT filter: a session is accepted only while it still exists
     * and has not gone idle; acceptance marks it used.
     */
    public boolean accept(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        Optional<RefreshTokenStore.Entry> found = store.findById(sessionId);
        if (found.isEmpty()) {
            return false;
        }
        if (isIdle(found.get().lastUsedAt(), now)) {
            store.deleteById(sessionId);
            return false;
        }
        return store.touch(sessionId, now);
    }

    private boolean isIdle(Instant lastUsedAt, Instant now) {
        return now.isAfter(lastUsedAt.plus(idleTimeout));
    }

    private static List<String> rolesOf(UserDetails user) {
        return user.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .toList();
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
