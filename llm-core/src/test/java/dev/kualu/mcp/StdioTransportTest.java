package dev.kualu.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the session against a real child process speaking NDJSON over stdio. */
class StdioTransportTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void fullRoundTripAgainstChildProcess() {
        try (StdioTransport transport = fakeServerTransport();
             McpSession session = new McpSession(transport)) {
            session.open(TIMEOUT);
            assertEquals("fake-mcp-server", session.serverName());
            assertEquals("2025-06-18", session.protocolVersion());

            List<McpRemoteTool> tools = session.listTools(TIMEOUT);
            assertEquals(List.of("echo"), tools.stream().map(McpRemoteTool::name).toList());

            JsonObject args = new JsonObject();
            args.addProperty("text", "hi");
            McpCallResult result = session.callTool("echo", args, TIMEOUT);
            assertFalse(result.error());
            assertTrue(result.content().get(0).getAsJsonObject().get("text").getAsString().startsWith("echo:"));

            session.ping(TIMEOUT);
            assertTrue(session.isOpen());
        }
    }

    @Test
    void processExitClosesTransportAndFailsRequests() throws Exception {
        StdioTransport transport = fakeServerTransport();
        McpSession session = new McpSession(transport);
        session.open(TIMEOUT);
        assertTrue(transport.isOpen());
        transport.close();
        // close() kills the process; the transport must report closed afterwards.
        assertFalse(transport.isOpen());
        assertThrows(McpException.class, () -> session.request("ping", null, Duration.ofSeconds(1)));
        session.close();
    }

    @Test
    void launchFailureIsReported() {
        StdioTransport transport = new StdioTransport(List.of("definitely-not-a-command-xyz"), null);
        McpSession session = new McpSession(transport);
        assertThrows(McpException.class, () -> session.open(Duration.ofSeconds(5)));
    }

    private static StdioTransport fakeServerTransport() {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path")
                + File.pathSeparator + new File("build/classes/java/test").getAbsolutePath()
                + File.pathSeparator + new File("build/classes/java/main").getAbsolutePath();
        return new StdioTransport(
                List.of(java, "-cp", classpath, FakeMcpServer.class.getName()), null);
    }
}
