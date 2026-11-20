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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 *
 * <p>With a {@link ScopeResolver} set, {@link #forUser} hands each owner their own
 * view: the workdir argument resolves inside that owner's workspace (escapes are
 * refused) and the child process gets a private environment — every CLI config
 * root ({@code GH_CONFIG_DIR}, {@code CLOUDSDK_CONFIG}, …) points into the
 * workspace, and shared credential variables never leak in from the server
 * process.
 */
public final class ExecTool implements AgentTool {

    /**
     * Per-owner execution scope. {@code workspace} is the root all relative
     * workdir arguments resolve against; {@code userEnv} is the owner's private
     * credential environment (e.g. {@code GH_TOKEN}), already validated to carry
     * no managed keys. {@code configEnv} carries the deployment's config-location
     * registry overrides (values support the {@code {workspace}} placeholder),
     * merged over {@link #CONFIG_ENV_TEMPLATES}.
     */
    public record Scope(Path workspace, Map<String, String> userEnv, Map<String, String> configEnv) {

        public Scope(Path workspace, Map<String, String> userEnv) {
            this(workspace, userEnv, Map.of());
        }

        public Scope {
            Objects.requireNonNull(workspace, "workspace");
            userEnv = userEnv == null ? Map.of() : Map.copyOf(userEnv);
            configEnv = configEnv == null ? Map.of() : Map.copyOf(configEnv);
        }
    }

    /** Resolves the scope for an authenticated owner; see {@link #forUser}. */
    @FunctionalInterface
    public interface ScopeResolver {
        Scope resolve(String owner);
    }

    /**
     * Config-location registry: where each CLI keeps its state is redirected into
     * the per-user workspace so no two owners ever share config or credentials.
     * Values may contain the {@code {workspace}} placeholder.
     */
    static final Map<String, String> CONFIG_ENV_TEMPLATES = Map.ofEntries(
            Map.entry("GH_CONFIG_DIR", "{workspace}/.gh"),
            Map.entry("CLOUDSDK_CONFIG", "{workspace}/.config/gcloud"),
            Map.entry("AWS_SHARED_CREDENTIALS_FILE", "{workspace}/.aws/credentials"),
            Map.entry("AWS_CONFIG_FILE", "{workspace}/.aws/config"),
            Map.entry("GIT_CONFIG_GLOBAL", "{workspace}/.gitconfig"),
            Map.entry("DOCKER_CONFIG", "{workspace}/.docker"),
            Map.entry("KUBECONFIG", "{workspace}/.kube/config"),
            Map.entry("AZURE_CONFIG_DIR", "{workspace}/.azure"),
            Map.entry("NPM_CONFIG_USERCONFIG", "{workspace}/.npmrc"),
            Map.entry("XDG_CONFIG_HOME", "{workspace}/.config"),
            Map.entry("XDG_DATA_HOME", "{workspace}/.local/share"),
            Map.entry("XDG_STATE_HOME", "{workspace}/.local/state"),
            Map.entry("XDG_CACHE_HOME", "{workspace}/.cache"));

    /** Credential variables stripped from the inherited env before a scoped run. */
    private static final Set<String> SHARED_CREDENTIAL_KEYS = Set.of("GH_TOKEN", "GITHUB_TOKEN");

    /** Keys removed from the inherited env whenever a per-user scope applies. */
    static final Set<String> SHARED_ENV_KEYS =
            Set.copyOf(java.util.stream.Stream.concat(
                    CONFIG_ENV_TEMPLATES.keySet().stream(), SHARED_CREDENTIAL_KEYS.stream()).toList());

    /**
     * Execution limits; see the constructor for the constraints.
     */
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
    private final String owner;
    private final ScopeResolver resolver;

    public ExecTool(Config config) {
        this(config, System.getProperty("os.name", ""));
    }

    ExecTool(Config config, String osName) {
        this(config, osName, null, null);
    }

    /** @param resolver per-owner scope resolution; {@code null} keeps single-user behaviour */
    public ExecTool(Config config, ScopeResolver resolver) {
        this(config, System.getProperty("os.name", ""), null, resolver);
    }

    private ExecTool(Config config, String osName, String owner, ScopeResolver resolver) {
        this.config = Objects.requireNonNull(config, "config");
        this.windows = osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        this.owner = owner;
        this.resolver = resolver;
    }

    @Override
    public AgentTool forUser(String owner) {
        if (resolver == null) {
            return this;
        }
        return new ExecTool(config, osName(), owner, resolver);
    }

    private String osName() {
        return windows ? "windows" : "linux";
    }

    @Override
    public String name() {
        return "exec";
    }

    @Override
    public String description() {
        if (owner == null) {
            return "Run a command in the system terminal (Windows: cmd.exe; Linux/macOS: /bin/sh) "
                    + "and return its output and exit code. Use it to inspect the system, manage files, "
                    + "run builds and scripts.";
        }
        return "Run a command in your private workspace (Windows: cmd.exe; Linux/macOS: /bin/sh) "
                + "and return its output and exit code. Use it to inspect the system, manage files, "
                + "run builds and scripts. The workdir argument is relative to your workspace root.";
    }

    @Override
    public String parametersJson() {
        String workdirDescription = owner == null
                ? "Working directory to run the command in. Defaults to the server working directory."
                : "Working directory relative to your workspace root (absolute paths inside the workspace "
                        + "are also accepted). Defaults to the workspace root.";
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "The command line to run in the system terminal."
                    },
                    "workdir": {
                      "type": "string",
                      "description": "%s"
                    }
                  },
                  "required": ["command"]
                }
                """.formatted(workdirDescription);
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

        String workdirArg = stringArg(args, "workdir");
        if (owner == null) {
            Path workdir = config.workdir();
            if (workdirArg != null && !workdirArg.isBlank()) {
                workdir = Path.of(workdirArg);
            }
            if (workdir != null && !Files.isDirectory(workdir)) {
                return "error: workdir is not a directory: " + workdir;
            }
            return run(command, workdir, null);
        }

        Scope scope;
        try {
            scope = resolver.resolve(owner);
        } catch (RuntimeException ex) {
            return "error: failed to resolve the workspace for this user: " + ex.getMessage();
        }
        if (scope == null) {
            return "error: no workspace is configured for this user";
        }
        Path workdir;
        try {
            workdir = scopedWorkdir(workdirArg, scope.workspace());
        } catch (IllegalArgumentException ex) {
            return "error: " + ex.getMessage();
        }
        return run(command, workdir, scopedEnv(scope));
    }

    /**
     * Resolves the workdir argument inside the owner's workspace. Relative paths
     * (and absolute paths that stay inside) are allowed; {@code ..} segments and
     * anything resolving outside the workspace are refused. Mirrors the office-mcp
     * output sandbox: reject early, then verify the resolved real path.
     */
    static Path scopedWorkdir(String workdirArg, Path workspace) {
        if (workdirArg == null || workdirArg.isBlank()) {
            return requireDirectory(workspace, "workspace");
        }
        Path requested;
        try {
            requested = Path.of(workdirArg);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("workdir is not a valid path: " + workdirArg);
        }
        for (Path part : requested) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("workdir must not contain '..': " + workdirArg);
            }
        }
        Path candidate = requested.isAbsolute() ? requested : workspace.resolve(requested);
        Path real;
        Path root;
        try {
            root = workspace.toRealPath();
            real = candidate.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("workdir is not a directory: " + workdirArg);
        }
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("workdir escapes the workspace: " + workdirArg);
        }
        return requireDirectory(real, "workdir");
    }

    private static Path requireDirectory(Path path, String label) {
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(label + " is not a directory: " + path);
        }
        return path;
    }

    /** The scoped run's extra environment: config roots into the workspace, then user secrets. */
    static Map<String, String> scopedEnv(Scope scope) {
        String workspace = scope.workspace().toString();
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : CONFIG_ENV_TEMPLATES.entrySet()) {
            env.put(entry.getKey(), entry.getValue().replace("{workspace}", workspace));
        }
        for (Map.Entry<String, String> entry : scope.configEnv().entrySet()) {
            env.put(entry.getKey(), entry.getValue().replace("{workspace}", workspace));
        }
        env.putAll(scope.userEnv());
        return env;
    }

    /**
     * Drops shared credential/config variables from the inherited environment and
     * overlays the scoped ones, so a scoped run can never see the server's
     * {@code GH_TOKEN} or a shared {@code GH_CONFIG_DIR}.
     */
    static void applyScopedEnv(Map<String, String> environment, Map<String, String> scopedEnv) {
        environment.keySet().removeAll(SHARED_ENV_KEYS);
        environment.putAll(scopedEnv);
    }

    private String run(String command, Path workdir, Map<String, String> scopedEnv) {
        ProcessBuilder builder = new ProcessBuilder(shell(command))
                .redirectErrorStream(true);
        if (workdir != null) {
            builder.directory(workdir.toFile());
        }
        if (scopedEnv != null) {
            applyScopedEnv(builder.environment(), scopedEnv);
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
