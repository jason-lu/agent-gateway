package dev.kualu.llm.tools;

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
}
