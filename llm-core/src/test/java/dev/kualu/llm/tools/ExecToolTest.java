package dev.kualu.llm.tools;

import dev.kualu.llm.AgentTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecToolTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    private final ExecTool tool = new ExecTool(ExecTool.Config.defaults());

    /** Picks the right command for the current platform's shell. */
    private static String command(String windowsCommand, String unixCommand) {
        return WINDOWS ? windowsCommand : unixCommand;
    }

    private static String json(String command) {
        return "{\"command\":" + quote(command) + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Compares paths independent of the platform's separator. */
    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }

    @Test
    void returnsOutputAndExitCode() {
        String result = tool.execute(json(command("echo hello", "echo hello")));

        assertTrue(result.startsWith("exit code: 0"), result);
        assertTrue(result.contains("hello"), result);
    }

    @Test
    void reportsANonZeroExitCode() {
        String result = tool.execute(json("exit 3"));

        assertTrue(result.startsWith("exit code: 3"), result);
    }

    @Test
    void stdoutAndStderrAreBothCaptured() {
        String result = tool.execute(json(command(
                "echo out1& echo err1 1>&2",
                "echo out1; echo err1 1>&2")));

        assertTrue(result.contains("out1"), result);
        assertTrue(result.contains("err1"), result);
    }

    @Test
    void aCommandOverrunningTheTimeoutIsKilledAndReported() {
        ExecTool quick = new ExecTool(new ExecTool.Config(Duration.ofSeconds(1), 8000, null));
        long startedAt = System.currentTimeMillis();

        String result = quick.execute(json(command("ping -n 30 127.0.0.1", "sleep 30")));

        long elapsedMs = System.currentTimeMillis() - startedAt;
        assertTrue(result.startsWith("exit code: -1 (timed out"), result);
        assertTrue(elapsedMs < 20_000, "the tool must not wait for the command to finish (took " + elapsedMs + "ms)");
    }

    @Test
    void longOutputIsTruncatedToTheLimit() {
        ExecTool capped = new ExecTool(new ExecTool.Config(Duration.ofSeconds(30), 100, null));
        String longText = "0123456789".repeat(20);

        String result = capped.execute(json("echo " + longText));

        assertTrue(result.contains("...[output truncated: kept 100 of "), result);
        assertTrue(result.indexOf("01234567890123456789") >= 0, result);
    }

    @Test
    void missingCommandIsARecoverableError() {
        assertEquals("error: missing required argument 'command'", tool.execute("{}"));
    }

    @Test
    void nonObjectArgumentsAreARecoverableError() {
        assertEquals("error: arguments must be a JSON object", tool.execute("[1,2]"));
    }

    @Test
    void invalidJsonIsARecoverableError() {
        assertTrue(tool.execute("{not json").startsWith("error: arguments were not valid JSON:"), tool.execute("{not json"));
    }

    @Test
    void workdirArgumentIsHonoured(@TempDir Path tempDir) {
        String result = tool.execute(
                "{\"command\":" + quote(command("cd", "pwd")) + ",\"workdir\":" + quote(tempDir.toString()) + "}");

        String normalized = result.replace('\\', '/');
        assertTrue(normalized.contains(tempDir.toString().replace('\\', '/')), result);
    }

    @Test
    void aMissingWorkdirIsARecoverableError() {
        String result = tool.execute("{\"command\":\"echo hi\",\"workdir\":\"Q:\\\\no-such-dir-xyz\"}");

        assertTrue(result.startsWith("error: workdir is not a directory:"), result);
    }

    @Test
    void configuredWorkdirIsUsedWhenTheArgumentsCarryNone(@TempDir Path tempDir) {
        ExecTool inTemp = new ExecTool(new ExecTool.Config(Duration.ofSeconds(30), 8000, tempDir));

        String result = inTemp.execute(json(command("cd", "pwd")));

        assertTrue(result.replace('\\', '/').contains(tempDir.toString().replace('\\', '/')), result);
    }

    @Test
    void descriptionAndSchemaAdvertiseTheTool() {
        assertEquals("exec", tool.name());
        assertTrue(tool.description().contains("cmd.exe"));
        assertTrue(tool.parametersJson().contains("\"command\""));
    }

    // --- per-user scoping (forUser / ScopeResolver) ---

    /** A tool bound to {@code owner}, running inside {@code workspace}. */
    private static AgentTool scopedTool(Path workspace, java.util.Map<String, String> userEnv) {
        return new ExecTool(
                new ExecTool.Config(Duration.ofSeconds(30), 8000, null),
                owner -> new ExecTool.Scope(workspace, userEnv))
                .forUser("alice");
    }

    @Test
    void withoutAResolverForUserReturnsTheSameTool() {
        assertTrue(tool.forUser("alice") == tool);
    }

    @Test
    void withAResolverForUserBindsTheOwner(@TempDir Path tempDir) {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        ExecTool resolverTool = new ExecTool(ExecTool.Config.defaults(), owner -> {
            seen.set(owner);
            return new ExecTool.Scope(tempDir, java.util.Map.of());
        });

        AgentTool bound = resolverTool.forUser("alice");
        bound.execute(json(command("echo hi", "echo hi")));

        assertEquals("alice", seen.get());
    }

    @Test
    void scopedRunDefaultsToTheWorkspace(@TempDir Path tempDir) {
        String result = scopedTool(tempDir, java.util.Map.of()).execute(json(command("cd", "pwd")));

        assertTrue(result.replace('\\', '/').contains(tempDir.toString().replace('\\', '/')), result);
    }

    @Test
    void scopedWorkdirRejectsDotDot(@TempDir Path tempDir) {
        String result = scopedTool(tempDir, java.util.Map.of())
                .execute("{\"command\":\"echo hi\",\"workdir\":\"../other\"}");

        assertTrue(result.startsWith("error: workdir must not contain '..'"), result);
    }

    @Test
    void scopedWorkdirRejectsPathsOutsideTheWorkspace(@TempDir Path tempDir, @TempDir Path outside) {
        String result = scopedTool(tempDir, java.util.Map.of())
                .execute("{\"command\":\"echo hi\",\"workdir\":" + quote(outside.toString()) + "}");

        assertTrue(result.startsWith("error: workdir escapes the workspace"), result);
    }

    @Test
    void scopedWorkdirAcceptsAbsolutePathsInsideTheWorkspace(@TempDir Path tempDir) throws Exception {
        Path inner = java.nio.file.Files.createDirectories(tempDir.resolve("projects"));
        String result = scopedTool(tempDir, java.util.Map.of())
                .execute("{\"command\":" + quote(command("cd", "pwd")) + ",\"workdir\":"
                        + quote(inner.toString()) + "}");

        assertTrue(result.replace('\\', '/').contains("projects"), result);
    }

    @Test
    void scopedWorkdirRejectsMissingDirectories(@TempDir Path tempDir) {
        String result = scopedTool(tempDir, java.util.Map.of())
                .execute("{\"command\":\"echo hi\",\"workdir\":\"no-such-dir\"}");

        assertTrue(result.startsWith("error: workdir is not a directory"), result);
    }

    @Test
    void scopedRunInjectsTheUsersTokenAndConfigDirs(@TempDir Path tempDir) {
        String result = scopedTool(tempDir, java.util.Map.of("GH_TOKEN", "user-token-abc"))
                .execute(json(command("echo %GH_TOKEN% %GH_CONFIG_DIR%", "echo $GH_TOKEN $GH_CONFIG_DIR")));

        assertTrue(result.contains("user-token-abc"), result);
        assertTrue(result.replace('\\', '/').contains(tempDir.toString().replace('\\', '/')), result);
    }

    @Test
    void scopedEnvPointsEveryConfigRootIntoTheWorkspace(@TempDir Path tempDir) {
        java.util.Map<String, String> env = ExecTool.scopedEnv(new ExecTool.Scope(tempDir,
                java.util.Map.of("GH_TOKEN", "user-token-abc")));

        assertEquals(normalized(tempDir.resolve(".gh")), normalized(Path.of(env.get("GH_CONFIG_DIR"))));
        assertEquals(normalized(tempDir.resolve(".config/gcloud")), normalized(Path.of(env.get("CLOUDSDK_CONFIG"))));
        assertEquals(normalized(tempDir.resolve(".config")), normalized(Path.of(env.get("XDG_CONFIG_HOME"))));
        assertEquals(normalized(tempDir.resolve(".gitconfig")), normalized(Path.of(env.get("GIT_CONFIG_GLOBAL"))));
        assertEquals("user-token-abc", env.get("GH_TOKEN"));
    }

    @Test
    void scopedEnvMergesConfigOverridesOverTheDefaults(@TempDir Path tempDir) {
        java.util.Map<String, String> env = ExecTool.scopedEnv(new ExecTool.Scope(tempDir,
                java.util.Map.of(),
                java.util.Map.of("GH_CONFIG_DIR", "{workspace}/custom-gh", "EXTRA_TOOL_CONFIG", "{workspace}/x")));

        assertEquals(normalized(tempDir.resolve("custom-gh")), normalized(Path.of(env.get("GH_CONFIG_DIR"))));
        assertEquals(normalized(tempDir.resolve("x")), normalized(Path.of(env.get("EXTRA_TOOL_CONFIG"))));
        assertEquals(normalized(tempDir.resolve(".config/gcloud")), normalized(Path.of(env.get("CLOUDSDK_CONFIG"))));
    }

    @Test
    void applyScopedEnvStripsSharedCredentialsAndKeepsTheRest() {
        java.util.Map<String, String> environment = new java.util.HashMap<>();
        environment.put("GH_TOKEN", "shared-token");
        environment.put("GH_CONFIG_DIR", "/home/service/.config/gh");
        environment.put("PATH", "/usr/bin");
        environment.put("MY_KEEP", "keep-me");

        ExecTool.applyScopedEnv(environment, java.util.Map.of("GH_TOKEN", "user-token-abc"));

        assertEquals("user-token-abc", environment.get("GH_TOKEN"));
        assertEquals("/usr/bin", environment.get("PATH"));
        assertEquals("keep-me", environment.get("MY_KEEP"));
        assertEquals(null, environment.get("GH_CONFIG_DIR"));
    }
}
