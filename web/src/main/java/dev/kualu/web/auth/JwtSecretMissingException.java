package dev.kualu.web.auth;

/**
 * Thrown when JWT signing is not configured. Surfaces a short startup
 * message via {@link JwtSecretFailureAnalyzer}.
 */
public class JwtSecretMissingException extends IllegalStateException {

    public JwtSecretMissingException(String message) {
        super(message);
    }
}
