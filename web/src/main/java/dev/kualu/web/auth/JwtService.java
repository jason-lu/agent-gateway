package dev.kualu.web.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import dev.kualu.web.config.SecurityProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class JwtService {

    private final SecretKey key;
    private final SecurityProperties properties;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        String secret = properties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new JwtSecretMissingException(
                    "agent.security.jwt-secret (or AGENT_JWT_SECRET) must be configured");
        }
        if (secret.length() < 32) {
            throw new JwtSecretMissingException(
                    "agent.security.jwt-secret must be at least 32 characters (got " + secret.length() + ")");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issueToken(String username, List<String> roles, String sessionId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.jwtTtl());
        return Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .claim("sid", sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public long expiresInSeconds() {
        return properties.jwtTtl().toSeconds();
    }
}
