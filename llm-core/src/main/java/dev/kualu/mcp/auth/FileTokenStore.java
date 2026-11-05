package dev.kualu.mcp.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * File-backed token storage (the PC default). Tokens are refresh credentials —
 * the file is kept in a private directory with owner-only permissions where the
 * filesystem supports them.
 */
public final class FileTokenStore implements McpTokenStore {

    private static final Logger log = LoggerFactory.getLogger(FileTokenStore.class);

    private final Path file;
    private final Object lock = new Object();

    public FileTokenStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Default location: {@code <user.home>/.personal-assistant/mcp-tokens.json}. */
    public static FileTokenStore defaultStore() {
        return new FileTokenStore(Path.of(System.getProperty("user.home"), ".personal-assistant", "mcp-tokens.json"));
    }

    @Override
    public Optional<StoredTokens> load(String serverName) {
        synchronized (lock) {
            JsonObject root = read();
            if (!root.has(serverName) || !root.get(serverName).isJsonObject()) {
                return Optional.empty();
            }
            JsonObject entry = root.getAsJsonObject(serverName);
            try {
                return Optional.of(new StoredTokens(
                        entry.get("accessToken").getAsString(),
                        entry.has("refreshToken") && entry.get("refreshToken").isJsonPrimitive()
                                ? entry.get("refreshToken").getAsString()
                                : null,
                        entry.has("expiresAt") ? entry.get("expiresAt").getAsLong() : Long.MAX_VALUE));
            } catch (RuntimeException ex) {
                log.warn("mcp token entry for {} is corrupt; ignoring", serverName);
                return Optional.empty();
            }
        }
    }

    @Override
    public void save(String serverName, StoredTokens tokens) {
        synchronized (lock) {
            JsonObject root = read();
            JsonObject entry = new JsonObject();
            entry.addProperty("accessToken", tokens.accessToken());
            if (tokens.refreshToken() != null) {
                entry.addProperty("refreshToken", tokens.refreshToken());
            }
            entry.addProperty("expiresAt", tokens.expiresAt());
            root.add(serverName, entry);
            write(root);
        }
    }

    @Override
    public void delete(String serverName) {
        synchronized (lock) {
            JsonObject root = read();
            if (root.remove(serverName) != null) {
                write(root);
            }
        }
    }

    private JsonObject read() {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
        } catch (IOException | RuntimeException ex) {
            log.warn("cannot read mcp token file {}: {}", file, ex.getMessage());
            return new JsonObject();
        }
    }

    private void write(JsonObject root) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
            restrictPermissions(file);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot write mcp token file " + file + ": " + ex.getMessage(), ex);
        }
    }

    /** Owner-only where POSIX permissions apply; best effort elsewhere. */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException | RuntimeException ex) {
            // non-POSIX filesystem (Windows ACLs are not modelled here)
        }
    }

    /** Exposed for tests: the backing file path. */
    Path file() {
        return file;
    }
}
