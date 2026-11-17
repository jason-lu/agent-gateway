package dev.kualu.llm.memory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Standing semantic-memory policy, injected as a request-only system message
 * whenever the memory tools are on offer. Wording lives in the resource file;
 * assembly stays in code (same boundary as the tool policy and the summarisation
 * instruction).
 */
public final class MemoryPolicy {

    /** Name of the classpath resource holding the policy. */
    static final String POLICY_RESOURCE = "memory-policy.txt";

    public static final String TEXT = load();

    private static String load() {
        try (InputStream in = MemoryPolicy.class.getResourceAsStream(POLICY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource "
                        + MemoryPolicy.class.getPackageName().replace('.', '/') + "/" + POLICY_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private MemoryPolicy() {
    }
}
