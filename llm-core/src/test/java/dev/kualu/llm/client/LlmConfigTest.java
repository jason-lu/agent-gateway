package dev.kualu.llm.client;

import dev.kualu.llm.LlmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadDotEnv_readsKeysCommentsAndQuotes() throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                # comment
                LLM_BASE_URL=https://api.example.com/v1

                LLM_API_KEY="sk-secret"
                LLM_MODEL='gpt-4o-mini'
                IGNORED=yes
                """, StandardCharsets.UTF_8);

        Map<String, String> values = LlmConfig.loadDotEnv(env);

        assertEquals("https://api.example.com/v1", values.get("LLM_BASE_URL"));
        assertEquals("sk-secret", values.get("LLM_API_KEY"));
        assertEquals("gpt-4o-mini", values.get("LLM_MODEL"));
        assertEquals("yes", values.get("IGNORED"));
    }

    @Test
    void loadDotEnv_missingFile_returnsEmpty() {
        Map<String, String> values = LlmConfig.loadDotEnv(tempDir.resolve("nope.env"));
        assertTrue(values.isEmpty());
    }

    @Test
    void fromEnvironment_readsDotEnvFile() throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                LLM_BASE_URL=https://llm.test/v1
                LLM_API_KEY=key-from-file
                LLM_MODEL=model-from-file
                """, StandardCharsets.UTF_8);

        LlmConfig config = LlmConfig.fromEnvironment(env);

        assertEquals("https://llm.test/v1", config.baseUrl());
        assertEquals("key-from-file", config.apiKey());
        assertEquals("model-from-file", config.model());
    }

    @Test
    void fromEnvironment_supportsAliasKeys() throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                BASE_URL=https://alias.test/v1
                API_KEY=alias-key
                MODEL=alias-model
                """, StandardCharsets.UTF_8);

        LlmConfig config = LlmConfig.fromEnvironment(env);

        assertEquals("https://alias.test/v1", config.baseUrl());
        assertEquals("alias-key", config.apiKey());
        assertEquals("alias-model", config.model());
    }

    @Test
    void fromEnvironment_missingValues_throws() throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "LLM_MODEL=only-model\n", StandardCharsets.UTF_8);

        LlmException ex = assertThrows(LlmException.class, () -> LlmConfig.fromEnvironment(env));
        assertTrue(ex.getMessage().contains("LLM_BASE_URL"));
    }

    @Test
    void fromEnvironment_idleTimeout_defaultsWhenAbsent() throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                LLM_BASE_URL=https://llm.test/v1
                LLM_API_KEY=key
                LLM_MODEL=model
                """, StandardCharsets.UTF_8);

        LlmConfig config = LlmConfig.fromEnvironment(env);

        assertEquals(LlmConfig.DEFAULT_IDLE_TIMEOUT, config.idleTimeout());
    }

    @Test
    void fromEnvironment_idleTimeout_readsSecondsAndDisablesWithZero() throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                LLM_BASE_URL=https://llm.test/v1
                LLM_API_KEY=key
                LLM_MODEL=model
                LLM_IDLE_TIMEOUT=45
                """, StandardCharsets.UTF_8);

        assertEquals(Duration.ofSeconds(45), LlmConfig.fromEnvironment(env).idleTimeout());

        Files.writeString(env, """
                LLM_BASE_URL=https://llm.test/v1
                LLM_API_KEY=key
                LLM_MODEL=model
                LLM_IDLE_TIMEOUT=0
                """, StandardCharsets.UTF_8);

        assertEquals(Duration.ZERO, LlmConfig.fromEnvironment(env).idleTimeout());
    }

    @Test
    void fromEnvironment_invalidIdleTimeout_throws() throws IOException {
        Path env = tempDir.resolve(".env");
        Files.writeString(env, """
                LLM_BASE_URL=https://llm.test/v1
                LLM_API_KEY=key
                LLM_MODEL=model
                LLM_IDLE_TIMEOUT=soon
                """, StandardCharsets.UTF_8);

        LlmException ex = assertThrows(LlmException.class, () -> LlmConfig.fromEnvironment(env));
        assertTrue(ex.getMessage().contains("LLM_IDLE_TIMEOUT"));
    }

    @Test
    void constructor_rejectsBlankValues() {
        LlmException ex = assertThrows(LlmException.class,
                () -> new LlmConfig("https://x.test", "  ", "model"));
        assertTrue(ex.getMessage().contains("apiKey"));
    }

    @Test
    void toString_hidesApiKey() {
        LlmConfig config = new LlmConfig("https://x.test", "sk-super-secret", "m");
        assertTrue(config.toString().contains("https://x.test"));
        assertTrue(config.toString().contains("m"));
        assertTrue(!config.toString().contains("sk-super-secret"));
    }
}
