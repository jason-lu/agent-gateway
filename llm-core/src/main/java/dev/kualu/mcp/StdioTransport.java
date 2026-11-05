package dev.kualu.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MCP transport over a child process: one JSON-RPC message per line (NDJSON) on
 * stdin/stdout, with stderr forwarded to the log. The process is killed on close.
 */
public final class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    private final List<String> command;
    private final Map<String, String> environment;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Process process;
    private volatile BufferedWriter stdin;

    /** @param command     argv-style launch command, e.g. {@code ["npx", "@playwright/mcp@latest"]} */
    public StdioTransport(List<String> command, Map<String, String> environment) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        this.command = List.copyOf(command);
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    @Override
    public void start(Consumer<JsonObject> onMessage, Runnable onClosed) {
        Objects.requireNonNull(onMessage, "onMessage");
        Objects.requireNonNull(onClosed, "onClosed");
        process = launch();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        Thread out = new Thread(() -> pumpStdout(onMessage), "mcp-stdio-out");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(this::pumpStderr, "mcp-stdio-err");
        err.setDaemon(true);
        err.start();
        Thread waiter = new Thread(() -> {
            try {
                int exit = process.waitFor();
                log.info("mcp server process exited: {} (exit={})", command, exit);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                if (!closed.get()) {
                    onClosed.run();
                }
            }
        }, "mcp-stdio-wait");
        waiter.setDaemon(true);
        waiter.start();
    }

    private Process launch() {
        try {
            return newProcess(command);
        } catch (IOException directFailure) {
            List<String> wrapped = wrapForShell(command);
            if (wrapped == null) {
                throw new McpException(
                        "failed to start mcp server process " + command + ": " + directFailure.getMessage(),
                        directFailure);
            }
            try {
                log.debug("direct spawn failed ({}), retrying through shell", directFailure.getMessage());
                return newProcess(wrapped);
            } catch (IOException shellFailure) {
                throw new McpException(
                        "failed to start mcp server process " + command + ": " + shellFailure.getMessage(),
                        shellFailure);
            }
        }
    }

    private static Process newProcess(List<String> argv) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(argv);
        return builder.start();
    }

    /**
     * Windows cannot spawn {@code .cmd}/{@code .bat} shims (npx, docker) directly;
     * route those launches through {@code cmd.exe /c}.
     */
    private static List<String> wrapForShell(List<String> command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return null;
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(command);
        return wrapped;
    }

    private void pumpStdout(Consumer<JsonObject> onMessage) {
        Process current = process;
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    onMessage.accept(JsonParser.parseString(line).getAsJsonObject());
                } catch (RuntimeException ex) {
                    log.warn("skipping unparseable mcp message: {}", ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.debug("mcp stdout closed: {}", ex.getMessage());
        }
    }

    private void pumpStderr() {
        Process current = process;
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.info("[mcp-server] {}", line);
                }
            }
        } catch (IOException ex) {
            log.debug("mcp stderr closed: {}", ex.getMessage());
        }
    }

    @Override
    public void send(JsonObject message) {
        BufferedWriter writer = stdin;
        if (closed.get() || writer == null || !isOpen()) {
            throw new McpException("mcp stdio transport is closed");
        }
        String json = message.toString();
        synchronized (writeLock) {
            try {
                writer.write(json);
                writer.write('\n');
                writer.flush();
            } catch (IOException ex) {
                throw new McpException("failed to write to mcp server process: " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public boolean isOpen() {
        Process current = process;
        return current != null && current.isAlive() && !closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Process current = process;
        if (current == null) {
            return;
        }
        current.destroy();
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }
}
