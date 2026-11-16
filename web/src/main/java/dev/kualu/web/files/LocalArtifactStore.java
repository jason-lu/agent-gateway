package dev.kualu.web.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Artifacts on local disk under a fixed root.
 *
 * <p>The office-mcp sandbox already refuses escapes at write time; this store
 * checks again at download time — the two checks are deliberate depth, since the
 * relative name reaches this class through an HTTP URL. Absolute paths, drive
 * letters and {@code ..} segments are rejected before any filesystem touch.
 */
public final class LocalArtifactStore implements ArtifactStore {

    private final Path root;

    public LocalArtifactStore(Path root) {
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
        String name = ArtifactNames.normalize(relative);
        Path normalized = root.resolve(name).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("path escapes the files dir");
        }
        return normalized;
    }

    @Override
    public boolean exists(String relativeName) {
        return Files.isRegularFile(resolve(relativeName));
    }

    @Override
    public long size(String relativeName) throws IOException {
        return Files.size(resolve(relativeName));
    }

    @Override
    public InputStream open(String relativeName) throws IOException {
        return Files.newInputStream(resolve(relativeName));
    }
}
