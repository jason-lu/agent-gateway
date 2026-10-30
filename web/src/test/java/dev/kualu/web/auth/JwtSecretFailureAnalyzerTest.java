package dev.kualu.web.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ApplicationContextException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtSecretFailureAnalyzerTest {

    @Test
    void analyzesMissingSecretThroughSpringWrappers() {
        Exception root = new ApplicationContextException("Unable to start web server",
                new RuntimeException("wrapper",
                        new JwtSecretMissingException("secret missing")));

        FailureAnalysis analysis = new JwtSecretFailureAnalyzer().analyze(root);

        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("secret missing"));
        assertTrue(analysis.getAction().contains("AGENT_JWT_SECRET"));
    }
}
