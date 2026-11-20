package dev.kualu.web.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local reset tokens; everything is lost on restart. */
public class InMemoryPasswordResetTokenStore implements PasswordResetTokenStore {

    private final ConcurrentHashMap<String, Token> byHash = new ConcurrentHashMap<>();

    @Override
    public void insert(Token token) {
        byHash.put(token.tokenHash(), token);
    }

    @Override
    public Optional<Token> find(String tokenHash) {
        return Optional.ofNullable(byHash.get(tokenHash));
    }

    @Override
    public boolean markUsed(String tokenHash, Instant at) {
        for (;;) {
            Token existing = byHash.get(tokenHash);
            if (existing == null || existing.used()) {
                return false;
            }
            Token spent = new Token(
                    existing.tokenHash(), existing.username(), existing.createdAt(), existing.expiresAt(), at);
            if (byHash.replace(tokenHash, existing, spent)) {
                return true;
            }
        }
    }

    @Override
    public void deleteAllFor(String username) {
        byHash.values().removeIf(token -> token.username().equals(username));
    }
}
