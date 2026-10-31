package dev.kualu;

import dev.kualu.llm.LlmClient;
import dev.kualu.llm.MemoryCompressor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        boolean streaming = true;
        for (String arg : args) {
            switch (arg) {
                case "--no-stream", "--full" -> streaming = false;
                case "--stream" -> streaming = true;
                case "--help", "-h" -> {
                    System.out.println("Usage: java dev.kualu.Main [--stream | --no-stream]");
                    System.out.println("  --stream     Stream replies as they arrive (default)");
                    System.out.println("  --no-stream  Wait for the full reply before printing");
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + arg + " (try --help)");
                    return;
                }
            }
        }

        LlmClient client = LlmClient.fromEnvironment();
        MemoryCompressor compressor = memoryCompressor(client);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        new ChatConsole(client, in, System.out, streaming, compressor).run();
    }

    /** Builds the compressor from {@code AGENT_MEMORY_*}; returns null when disabled. */
    static MemoryCompressor memoryCompressor(LlmClient client) {
        if (!Boolean.parseBoolean(envOr("AGENT_MEMORY_ENABLED", "true"))) {
            log.info("memory-compress configured: enabled=false");
            return null;
        }
        int maxMessages = Integer.parseInt(envOr("AGENT_MEMORY_MAX_MESSAGES", "20"));
        int keepMessages = Integer.parseInt(envOr("AGENT_MEMORY_KEEP_MESSAGES", "8"));
        String instruction = envOr("AGENT_MEMORY_INSTRUCTION", "");
        log.info("memory-compress configured: enabled=true, max-messages={}, keep-messages={}",
                maxMessages, keepMessages);
        return new MemoryCompressor(client, maxMessages, keepMessages, instruction);
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
