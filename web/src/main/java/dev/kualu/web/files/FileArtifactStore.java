package dev.kualu.web.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Maps tool-reported relative names to files under a fixed root.
 *
 * <p>The office-mcp sandbox already refuses escapes at write time; this store
 * checks again at download time — the two checks are deliberate depth, since the
 * relative name reaches this class through an HTTP URL. Absolute paths, drive
 * letters and {@code ..} segments are rejected before any filesystem touch.
 */
public final class FileArtifactStore {

    private final Path root;

    public FileArtifactStore(Path root) {
        Objects.requireNonNull(root, "root");
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create agent.files.dir: " + this.root, e);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Resolves a relative name like {@code reports/q1.xlsx} inside the root.
     *
     * @throws IllegalArgumentException when the name is empty, absolute, or escapes the root
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        String posix = relative.trim().replace('\\', '/');
        if (posix.startsWith("/") || posix.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("path must be relative");
        }
        Path candidate = root;
        for (String part : posix.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("path must not contain '..'");
            }
            candidate = candidate.resolve(part);
        }
        Path normalized = candidate.normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the files dir");
        }
        return normalized;
    }
}
