package dev.kualu.web.tools;

import dev.kualu.llm.tools.ExecTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Per-owner workspace layout for the exec tool: every owner gets
 * {@code <root>/<dir(owner)>}, created on demand with private permissions. The
 * workspace is the resolution root for the workdir argument and the home of every
 * redirected CLI config directory. Stored credential values are additionally
 * materialized to {@code .secrets/<KEY>} inside the workspace so file-oriented
 * tools (e.g. {@code gcloud auth activate-service-account --key-file}) can use them
 * without the secret ever passing through chat.
 */
public final class ExecScopeResolver implements ExecTool.ScopeResolver {

    private final Path root;
    private final UserToolEnvStore store;
    private final Map<String, String> configEnv;

    public ExecScopeResolver(Path root, UserToolEnvStore store, Map<String, String> configEnv) {
        this.root = root.toAbsolutePath().normalize();
        this.store = store;
        this.configEnv = configEnv == null ? Map.of() : Map.copyOf(configEnv);
    }

    @Override
    public ExecTool.Scope resolve(String owner) {
        Path workspace = root.resolve(directoryNameFor(owner));
        createPrivateDirectory(workspace);
        Map<String, String> userEnv = store.listByOwner(owner);
        materializeSecrets(workspace, userEnv);
        return new ExecTool.Scope(workspace, userEnv, configEnv);
    }

    /**
     * Filesystem-safe directory name for an owner: readable where possible,
     * collision-free even when two owners sanitize to the same string (the short
     * digest of the original name is appended whenever sanitizing changed it).
     */
    static String directoryNameFor(String owner) {
        String sanitized = owner.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) {
            sanitized = "user";
        }
        if (sanitized.equals(owner)) {
            return sanitized;
        }
        return sanitized + "-" + shortDigest(owner);
    }

    private static String shortDigest(String owner) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Writes each secret to {@code .secrets/<KEY>} (private perms) for file-based consumers. */
    private static void materializeSecrets(Path workspace, Map<String, String> userEnv) {
        if (userEnv.isEmpty()) {
            return;
        }
        Path secrets = workspace.resolve(".secrets");
        createPrivateDirectory(secrets);
        for (Map.Entry<String, String> entry : userEnv.entrySet()) {
            try {
                Path file = secrets.resolve(entry.getKey());
                Files.writeString(file, entry.getValue());
                setPermissions(file, false);
            } catch (IOException ex) {
                throw new IllegalStateException("failed to materialize secret " + entry.getKey(), ex);
            }
        }
    }

    private static void createPrivateDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            setPermissions(directory, true);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create workspace directory " + directory, ex);
        }
    }

    /** POSIX only; on other filesystems the default ACL applies. */
    private static void setPermissions(Path path, boolean directory) {
        try {
            Set<PosixFilePermission> perms = directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX volumes: nothing to tighten
        }
    }
}
