package dev.kualu.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LLM connection settings: base URL, API key, model name, and the stream idle timeout.
 * Values are resolved from the process environment first, then from a {@code .env} file.
 */
public final class LlmConfig {

    /** Idle timeout applied when none is configured. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(90);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration idleTimeout;

    public LlmConfig(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, DEFAULT_IDLE_TIMEOUT);
    }

    public LlmConfig(String baseUrl, String apiKey, String model, Duration idleTimeout) {
        this.baseUrl = requireText(baseUrl, "baseUrl");
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    /**
     * How long a stream may stay silent before it is aborted.
     * {@link Duration#ZERO} disables the idle watchdog.
     */
    public Duration idleTimeout() {
        return idleTimeout;
    }

    /** Loads config from environment variables and {@code .env} in the working directory. */
    public static LlmConfig fromEnvironment() {
        return fromEnvironment(Path.of(".env"));
    }

    /**
     * Loads config from environment variables, then the given dotenv file.
     * Environment variables always win over {@code .env} values.
     */
    public static LlmConfig fromEnvironment(Path dotenvFile) {
        Map<String, String> dotenv = loadDotEnv(dotenvFile);
        return new LlmConfig(
                resolve("LLM_BASE_URL", "BASE_URL", dotenv),
                resolve("LLM_API_KEY", "API_KEY", dotenv),
                resolve("LLM_MODEL", "MODEL", dotenv),
                resolveIdleTimeout(dotenv));
    }

    private static Duration resolveIdleTimeout(Map<String, String> dotenv) {
        String value = firstNonBlank(
                System.getenv("LLM_IDLE_TIMEOUT"),
                System.getenv("IDLE_TIMEOUT"),
                dotenv.get("LLM_IDLE_TIMEOUT"),
                dotenv.get("IDLE_TIMEOUT"));
        if (value == null) {
            return DEFAULT_IDLE_TIMEOUT;
        }
        long seconds;
        try {
            seconds = Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new LlmException("Invalid LLM_IDLE_TIMEOUT (expected whole seconds): " + value);
        }
        return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ZERO;
    }

    private static String resolve(String primaryKey, String aliasKey, Map<String, String> dotenv) {
        String value = firstNonBlank(
                System.getenv(primaryKey),
                System.getenv(aliasKey),
                dotenv.get(primaryKey),
                dotenv.get(aliasKey));
        if (value == null) {
            throw new LlmException("Missing LLM config: set " + primaryKey
                    + " (or " + aliasKey + ") in the environment or in .env");
        }
        return value;
    }

    static Map<String, String> loadDotEnv(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = stripQuotes(line.substring(eq + 1).trim());
                if (!key.isEmpty()) {
                    values.put(key, value);
                }
            }
            return values;
        } catch (IOException e) {
            throw new LlmException("Failed to read .env file " + file + ": " + e.getMessage(), e);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new LlmException("LLM config " + name + " must not be blank");
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return "LlmConfig{baseUrl=" + baseUrl + ", model=" + model + ", apiKey=***}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LlmConfig other)) {
            return false;
        }
        return baseUrl.equals(other.baseUrl)
                && apiKey.equals(other.apiKey)
                && model.equals(other.model)
                && idleTimeout.equals(other.idleTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl, apiKey, model, idleTimeout);
    }
}
