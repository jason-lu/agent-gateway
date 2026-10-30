package dev.kualu.web.auth;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Prints a short action item instead of a raw bean stack trace when the
 * JWT secret is missing or too short.
 */
public class JwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<JwtSecretMissingException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, JwtSecretMissingException cause) {
        return new FailureAnalysis(
                cause.getMessage(),
                "Set AGENT_JWT_SECRET (or agent.security.jwt-secret) to a random string of at least 32 characters, then start again.\n\n"
                        + "  PowerShell:  $env:AGENT_JWT_SECRET = '<at-least-32-chars>'\n"
                        + "  bash:        export AGENT_JWT_SECRET='<at-least-32-chars>'\n"
                        + "  .env:        AGENT_JWT_SECRET=<at-least-32-chars>",
                cause);
    }
}
