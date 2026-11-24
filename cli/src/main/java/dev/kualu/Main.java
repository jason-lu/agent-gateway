package dev.kualu;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.tools.ExecTool;
import dev.kualu.llm.tools.WebSearchTool;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.mcp.McpConnectionManager;
import dev.kualu.mcp.McpServerConfig;
import dev.kualu.mcp.config.McpJsonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        boolean streaming = true;
        boolean noModeration = false;
        for (String arg : args) {
            switch (arg) {
                case "--no-stream", "--full" -> streaming = false;
                case "--stream" -> streaming = true;
                case "--no-moderation" -> noModeration = true;
                case "--help", "-h" -> {
                    System.out.println("Usage: java dev.kualu.Main [--stream | --no-stream] [--no-moderation]");
                    System.out.println("  --stream          Stream replies as they arrive (default)");
                    System.out.println("  --no-stream       Wait for the full reply before printing");
                    System.out.println("  --no-moderation   Disable content moderation (debug only)");
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + arg + " (try --help)");
                    return;
                }
            }
        }

        LlmClient client = LlmClient.fromEnvironment();
        MemoryCompressor compressor = memoryCompressor(client);
        var moderator = noModeration ? null : contentModerator(client);
        if (noModeration) {
            log.warn("content moderation disabled by --no-moderation: nothing filters input or output");
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        McpConnectionManager mcp = mcpManager();
        List<AgentTool> staticTools = new ArrayList<>();
        staticTools.addAll(execTools());
        staticTools.addAll(webSearchTools());
        try {
            new ChatConsole(client, in, System.out, streaming, compressor, () -> {
                List<AgentTool> all = new ArrayList<>(staticTools);
                all.addAll(mcp.tools());
                return all;
            }, moderator).run();
        } finally {
            mcp.close();
        }
    }

    /**
     * Content moderation from {@code AGENT_MODERATION_*}. Off by default on the
     * console (the web gateway defaults on); the lexicon is the shared skeleton
     * at {@code classpath:moderation/lexicon.json} unless a path is configured.
     */
    static dev.kualu.llm.moderation.ContentModerator contentModerator(LlmClient client) {
        if (!Boolean.parseBoolean(envOr("AGENT_MODERATION_ENABLED", "false"))) {
            return null;
        }
        boolean flagMode = "flag".equalsIgnoreCase(envOr("AGENT_MODERATION_MODE", "block"));
        var policy = new dev.kualu.llm.moderation.ModerationPolicy(
                flagMode ? dev.kualu.llm.moderation.Mode.FLAG : dev.kualu.llm.moderation.Mode.BLOCK);
        String lexiconPath = envOr("AGENT_MODERATION_LEXICON_PATH", "classpath:moderation/lexicon.json");
        var lexicon = new dev.kualu.llm.moderation.LexiconModerator(policy, lexiconPath);
        dev.kualu.llm.moderation.LlmModerator classifier = null;
        if (Boolean.parseBoolean(envOr("AGENT_MODERATION_LLM_ENABLED", "true"))) {
            classifier = new dev.kualu.llm.moderation.LlmModerator(
                    () -> client,
                    Duration.ofMillis(Long.parseLong(envOr("AGENT_MODERATION_LLM_TIMEOUT_MS", "800"))),
                    0.7,
                    policy);
        }
        log.info("moderation configured: mode={}, lexicon={}, llm={}",
                flagMode ? "flag" : "block", lexiconPath, classifier != null);
        return new dev.kualu.llm.moderation.ContentModerator(lexicon, classifier, policy);
    }

    /** Builds the MCP manager from {@code AGENT_MCP_*} and the {@code mcp.json} file. */
    static McpConnectionManager mcpManager() {
        if (!Boolean.parseBoolean(envOr("AGENT_MCP_ENABLED", "true"))) {
            log.info("mcp configured: enabled=false");
            return new McpConnectionManager(List.of());
        }
        String path = envOr("AGENT_MCP_CONFIG", "./mcp.json");
        List<McpServerConfig> servers = McpJsonConfig.load(Path.of(path));
        log.info("mcp configured: {} server(s) from {}", servers.size(), path);
        McpConnectionManager manager = new McpConnectionManager(
                servers,
                McpConnectionManager.DEFAULT_HANDSHAKE_TIMEOUT,
                Duration.ofSeconds(Long.parseLong(envOr("AGENT_MCP_TIMEOUT", "60"))),
                Integer.parseInt(envOr("AGENT_MCP_OUTPUT_LIMIT", "8000")),
                Duration.ofSeconds(Long.parseLong(envOr("AGENT_MCP_REFRESH", "300"))));
        manager.start();
        return manager;
    }

    /** Builds the tool list from {@code AGENT_EXEC_*}; empty when the exec tool is disabled. */
    static List<AgentTool> execTools() {
        if (!Boolean.parseBoolean(envOr("AGENT_EXEC_ENABLED", "true"))) {
            log.info("exec-tool configured: enabled=false");
            return List.of();
        }
        Duration timeout = Duration.ofSeconds(Long.parseLong(envOr("AGENT_EXEC_TIMEOUT", "60")));
        int outputLimit = Integer.parseInt(envOr("AGENT_EXEC_OUTPUT_LIMIT", "8000"));
        String workdir = envOr("AGENT_EXEC_WORKDIR", "");
        log.info("exec-tool configured: enabled=true, timeout={}s, output-limit={}, workdir={}",
                timeout.toSeconds(), outputLimit, workdir.isBlank() ? "(inherited)" : workdir);
        return List.of(new ExecTool(new ExecTool.Config(
                timeout, outputLimit, workdir.isBlank() ? null : Path.of(workdir))));
    }

    /**
     * Builds the web-search tool from {@code AGENT_WEBSEARCH_*} / {@code TAVILY_API_KEY};
     * empty when disabled or no API key is configured.
     */
    static List<AgentTool> webSearchTools() {
        if (!Boolean.parseBoolean(envOr("AGENT_WEBSEARCH_ENABLED", "true"))) {
            log.info("websearch-tool configured: enabled=false");
            return List.of();
        }
        String apiKey = envOr("TAVILY_API_KEY", "").strip();
        if (apiKey.isEmpty()) {
            log.info("websearch-tool configured: enabled but no TAVILY_API_KEY - tool not registered");
            return List.of();
        }
        String baseUrl = envOr("AGENT_WEBSEARCH_BASE_URL", WebSearchTool.Config.DEFAULT_BASE_URL);
        int maxResults = Integer.parseInt(envOr("AGENT_WEBSEARCH_MAX_RESULTS", "5"));
        int outputLimit = Integer.parseInt(envOr("AGENT_WEBSEARCH_OUTPUT_LIMIT", "8000"));
        Duration timeout = Duration.ofSeconds(Long.parseLong(envOr("AGENT_WEBSEARCH_TIMEOUT", "30")));
        boolean includeAnswer = Boolean.parseBoolean(envOr("AGENT_WEBSEARCH_INCLUDE_ANSWER", "true"));
        log.info("websearch-tool configured: enabled=true, base-url={}, max-results={}, output-limit={},"
                        + " timeout={}s, include-answer={}",
                baseUrl, maxResults, outputLimit, timeout.toSeconds(), includeAnswer);
        return List.of(new WebSearchTool(new WebSearchTool.Config(
                apiKey, baseUrl, maxResults, outputLimit, timeout, includeAnswer)));
    }

    /** Builds the compressor from {@code AGENT_MEMORY_*}; returns null when disabled. */
    static MemoryCompressor memoryCompressor(LlmClient client) {
        if (!Boolean.parseBoolean(envOr("AGENT_MEMORY_ENABLED", "true"))) {
            log.info("memory-compress configured: enabled=false");
            return null;
        }
        int maxTokens = Integer.parseInt(envOr("AGENT_MEMORY_MAX_TOKENS", "500000"));
        int keepTokens = Integer.parseInt(envOr("AGENT_MEMORY_KEEP_TOKENS", "4096"));
        int maxSummaryChars = Integer.parseInt(envOr("AGENT_MEMORY_MAX_SUMMARY_CHARS", "4000"));
        String instruction = envOr("AGENT_MEMORY_INSTRUCTION", "");
        log.info("memory-compress configured: enabled=true, max-tokens={}, keep-tokens={}, max-summary-chars={}",
                maxTokens, keepTokens, maxSummaryChars);
        return new MemoryCompressor(client, maxTokens, keepTokens, instruction, maxSummaryChars);
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
