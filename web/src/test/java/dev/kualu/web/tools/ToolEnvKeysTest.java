package dev.kualu.web.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolEnvKeysTest {

    @Test
    void acceptsPlainCredentialKeys() {
        assertDoesNotThrow(() -> ToolEnvKeys.validate("GH_TOKEN", "ghp_abc"));
        assertDoesNotThrow(() -> ToolEnvKeys.validate("NPM_TOKEN", "npm_abc"));
        assertDoesNotThrow(() -> ToolEnvKeys.validate("GCLOUD_SA_KEY", "{\"type\":\"service_account\"}"));
    }

    @Test
    void rejectsMalformedKeys() {
        assertThrows(IllegalArgumentException.class, () -> ToolEnvKeys.validate("gh_token", "v"));
        assertThrows(IllegalArgumentException.class, () -> ToolEnvKeys.validate("1KEY", "v"));
        assertThrows(IllegalArgumentException.class, () -> ToolEnvKeys.validate("", "v"));
        assertThrows(IllegalArgumentException.class, () -> ToolEnvKeys.validate(null, "v"));
    }

    @Test
    void rejectsManagedConfigKeys() {
        for (String key : new String[] {
                "GH_CONFIG_DIR", "CLOUDSDK_CONFIG", "XDG_CONFIG_HOME", "GIT_CONFIG_GLOBAL",
                "HOME", "PATH", "LD_PRELOAD", "PYTHONPATH", "JAVA_TOOL_OPTIONS"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> ToolEnvKeys.validate(key, "v"), key);
            assertTrue(ex.getMessage().contains("managed by the server"), key);
        }
    }

    @Test
    void rejectsBlankAndOversizedValues() {
        assertThrows(IllegalArgumentException.class, () -> ToolEnvKeys.validate("GH_TOKEN", " "));
        assertThrows(IllegalArgumentException.class,
                () -> ToolEnvKeys.validate("GH_TOKEN", "x".repeat(ToolEnvKeys.MAX_VALUE_LENGTH + 1)));
    }
}
