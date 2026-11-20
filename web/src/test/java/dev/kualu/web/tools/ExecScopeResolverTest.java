package dev.kualu.web.tools;

import dev.kualu.llm.tools.ExecTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecScopeResolverTest {

    private static UserToolEnvStore storeWith(Map<String, String> entries) {
        InMemoryUserToolEnvStore store = new InMemoryUserToolEnvStore();
        entries.forEach((key, value) -> store.save("ada", key, value));
        return store;
    }

    @Test
    void workspaceIsCreatedPerOwner(@TempDir Path root) {
        ExecScopeResolver resolver = new ExecScopeResolver(root, storeWith(Map.of()), Map.of());

        ExecTool.Scope ada = resolver.resolve("ada");
        ExecTool.Scope bob = resolver.resolve("bob");

        assertTrue(Files.isDirectory(ada.workspace()));
        assertTrue(Files.isDirectory(bob.workspace()));
        assertNotEquals(ada.workspace(), bob.workspace());
    }

    @Test
    void awkwardOwnersGetCollisionFreeDirectoryNames() {
        assertEquals("ada", ExecScopeResolver.directoryNameFor("ada"));
        String first = ExecScopeResolver.directoryNameFor("张三");
        String second = ExecScopeResolver.directoryNameFor("张四");
        assertTrue(first.startsWith("___-") || first.contains("-"), first);
        assertNotEquals(first, second);
        assertNotEquals(ExecScopeResolver.directoryNameFor("a/b"), ExecScopeResolver.directoryNameFor("a_b"));
    }

    @Test
    void userEnvRidesAlongAndSecretsAreMaterialized(@TempDir Path root) throws IOException {
        ExecScopeResolver resolver = new ExecScopeResolver(root,
                storeWith(Map.of("GH_TOKEN", "user-token-abc", "GCLOUD_SA_KEY", "{\"k\":1}")), Map.of());

        ExecTool.Scope scope = resolver.resolve("ada");

        assertEquals("user-token-abc", scope.userEnv().get("GH_TOKEN"));
        Path secret = scope.workspace().resolve(".secrets").resolve("GH_TOKEN");
        assertTrue(Files.isRegularFile(secret));
        assertEquals("user-token-abc", Files.readString(secret));
        assertEquals("{\"k\":1}", Files.readString(scope.workspace().resolve(".secrets").resolve("GCLOUD_SA_KEY")));
    }

    @Test
    void emptySecretsLeaveNoTraces(@TempDir Path root) {
        ExecScopeResolver resolver = new ExecScopeResolver(root, storeWith(Map.of()), Map.of());

        ExecTool.Scope scope = resolver.resolve("ada");

        assertFalse(Files.exists(scope.workspace().resolve(".secrets")));
    }

    @Test
    void configEnvOverridesArePassedThrough(@TempDir Path root) {
        ExecScopeResolver resolver = new ExecScopeResolver(root,
                storeWith(Map.of()), Map.of("GH_CONFIG_DIR", "{workspace}/custom-gh"));

        ExecTool.Scope scope = resolver.resolve("ada");

        assertEquals(Map.of("GH_CONFIG_DIR", "{workspace}/custom-gh"), scope.configEnv());
    }
}
