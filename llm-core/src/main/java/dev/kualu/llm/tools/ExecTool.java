package dev.kualu.llm.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import dev.kualu.llm.AgentTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a command in the system terminal and returns its combined output.
 *
 * <p>Windows uses {@code cmd.exe /c} (console switched to UTF-8 code page 65001 for
 * the command); Linux and macOS use {@code /bin/sh -c}. The command is killed and
 * reported when it overruns the timeout, and the output is truncated to the
 * configured limit so one command cannot flood the conversation.
 */
public final class ExecTool implements AgentTool {

    private static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "The command line to run in the system terminal."
                },
                "workdir": {
                  "type": "string",
                  "description": "Working directory to run the command in. Defaults to the server working directory."
                }
              },
              "required": ["command"]
            }
            """;

    /** Execution limits; see the constructor for the constraints. */
    public record Config(Duration timeout, int outputLimit, Path workdir) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
        public static final int DEFAULT_OUTPUT_LIMIT = 8000;

        public Config {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (outputLimit <= 0) {
                throw new IllegalArgumentException("outputLimit must be positive");
            }
        }

        /** Defaults: 60 s timeout, 8000-character output cap, inherited working directory. */
        public static Config defaults() {
            return new Config(DEFAULT_TIMEOUT, DEFAULT_OUTPUT_LIMIT, null);
        }
    }

    private final Config config;
    private final boolean windows;

    public ExecTool(Config config) {
        this(config, System.getProperty("os.name", ""));
    }

    ExecTool(Config config, String osName) {
        this.config = Objects.requireNonNull(config, "config");
        this.windows = osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    @Override
    public String name() {
        return "exec";
    }

    @Override
    public String description() {
        return "Run a command in the system terminal (Windows: cmd.exe; Linux/macOS: /bin/sh) "
                + "and return its output and exit code. Use it to inspect the system, manage files, "
                + "run builds and scripts.";
    }

    @Override
    public String parametersJson() {
        return PARAMETERS_JSON;
    }

    @Override
    public String execute(String argumentsJson) {
        JsonObject args;
        try {
            JsonElement parsed = argumentsJson == null || argumentsJson.isBlank()
                    ? null
                    : JsonParser.parseString(argumentsJson);
            if (parsed == null || !parsed.isJsonObject()) {
                return "error: arguments must be a JSON object";
            }
            args = parsed.getAsJsonObject();
        } catch (JsonSyntaxException ex) {
            return "error: arguments were not valid JSON: " + ex.getMessage();
        }

        String command = stringArg(args, "command");
        if (command == null || command.isBlank()) {
            return "error: missing required argument 'command'";
        }

        Path workdir = config.workdir();
        String workdirArg = stringArg(args, "workdir");
        if (workdirArg != null && !workdirArg.isBlank()) {
            workdir = Path.of(workdirArg);
        }
        if (workdir != null && !Files.isDirectory(workdir)) {
            return "error: workdir is not a directory: " + workdir;
        }

        return run(command, workdir);
    }

    private String run(String command, Path workdir) {
        ProcessBuilder builder = new ProcessBuilder(shell(command))
                .redirectErrorStream(true);
        if (workdir != null) {
            builder.directory(workdir.toFile());
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            return "error: failed to start the command: " + ex.getMessage();
        }

        CompletableFuture<byte[]> output = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                output.complete(process.getInputStream().readAllBytes());
            } catch (IOException ex) {
                output.completeExceptionally(ex);
            }
        });

        boolean timedOut = false;
        try {
            if (!process.waitFor(config.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                destroyTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            destroyTree(process);
            Thread.currentThread().interrupt();
            return "error: interrupted while waiting for the command";
        }

        byte[] bytes;
        try {
            bytes = output.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "error: interrupted while reading the command output";
        } catch (ExecutionException ex) {
            String reason = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            return "error: failed to read the command output: " + reason;
        } catch (TimeoutException ex) {
            return "error: timed out while reading the command output";
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        if (timedOut) {
            result.append("exit code: -1 (timed out after ").append(config.timeout().toSeconds())
                    .append("s, process killed)\n");
        } else {
            result.append("exit code: ").append(process.exitValue()).append('\n');
        }
        result.append(truncate(text));
        return result.toString();
    }

    /**
     * Kills the whole process tree. The shell alone is not enough: children
     * (e.g. the command it spawned) keep the output pipe open, and the reader
     * would never see EOF.
     */
    private static void destroyTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Windows runs through {@code cmd.exe /c}; everything else through {@code /bin/sh -c}. */
    private List<String> shell(String command) {
        if (windows) {
            return List.of("cmd.exe", "/c", "chcp 65001 >nul & " + command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    private String truncate(String text) {
        if (text.length() <= config.outputLimit()) {
            return text;
        }
        int kept = config.outputLimit();
        return text.substring(0, kept)
                + "\n...[output truncated: kept " + kept + " of " + text.length() + " characters]";
    }

    private static String stringArg(JsonObject args, String key) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }
}
