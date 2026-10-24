package dev.kualu;

import dev.kualu.llm.LlmClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Main {

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
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        new ChatConsole(client, in, System.out, streaming).run();
    }
}
