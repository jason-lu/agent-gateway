package dev.kualu.web.tools;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Key rules for {@link UserToolEnvStore} entries: upper-case env names only, and the
 * variables the workspace machinery manages (config-location roots, interpreter and
 * loader overrides) are reserved so a personal credential can never rewire them.
 */
public final class ToolEnvKeys {

    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /** Longest accepted secret value; keys, tokens and small JSON blobs fit well under this. */
    public static final int MAX_VALUE_LENGTH = 16_384;

    private static final Set<String> RESERVED = Set.of(
            // managed by the per-workspace config-env registry (see ExecTool.CONFIG_ENV_TEMPLATES)
            "GH_CONFIG_DIR", "CLOUDSDK_CONFIG", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
            "GIT_CONFIG_GLOBAL", "DOCKER_CONFIG", "KUBECONFIG", "AZURE_CONFIG_DIR", "NPM_CONFIG_USERCONFIG",
            "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME",
            // process/interpreter/loader level: never user-settable
            "HOME", "USERPROFILE", "PATH", "PATHEXT", "LD_PRELOAD", "LD_LIBRARY_PATH",
            "PYTHONPATH", "PYTHONHOME", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH");

    private ToolEnvKeys() {
    }

    /** @throws IllegalArgumentException with a user-facing message when the key or value is not allowed */
    public static void validate(String key, String value) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "key must match [A-Z][A-Z0-9_]* and be at most 64 characters: " + key);
        }
        if (RESERVED.contains(key)) {
            throw new IllegalArgumentException("key is managed by the server and cannot be set here: " + key);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("value must be at most " + MAX_VALUE_LENGTH + " characters");
        }
    }
}
