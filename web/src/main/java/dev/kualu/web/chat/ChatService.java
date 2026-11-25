package dev.kualu.web.chat;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.llm.metrics.TurnTimings;
import dev.kualu.llm.tools.ToolLoop;
import dev.kualu.mcp.McpConnectionManager;
import dev.kualu.web.api.ApiDtos.HistoryMessage;
import dev.kualu.web.api.ApiDtos.RawMessage;
import dev.kualu.web.api.ApiDtos.RawToolCall;
import dev.kualu.web.llm.LlmSettingsService;
import dev.kualu.web.memory.SemanticMemory;
import dev.kualu.web.mcp.UserMcpRegistry;
import dev.kualu.web.metrics.TurnMetrics;
import dev.kualu.web.session.ImageRef;
import dev.kualu.web.session.Session;
import dev.kualu.web.session.SessionStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Turns HTTP chat operations into llm-core calls and session updates.
 * Tool calls (exec + MCP tools) are run by {@link ToolLoop} inside the turn;
 * the tool list is re-read every turn so MCP tools can come and go.
 *
 * <p>Memory compression runs after the turn, on a background executor: the fold
 * plan is taken under the session lock, the summarisation call runs outside it
 * (so a slow model cannot block the next turn), and the fold is applied under
 * the lock again. The turn that crosses the threshold therefore pays one
 * uncompressed payload instead of waiting for the summariser.
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Listing titles stay one short line. */
    private static final int TITLE_MAX_CHARS = 40;

    private final SessionStore sessionStore;
    private final LlmSettingsService llmSettings;
    private final MemoryCompressor compressor;
    private final List<AgentTool> staticTools;
    private final ObjectProvider<McpConnectionManager> mcpManagers;
    private final ObjectProvider<UserMcpRegistry> userMcpRegistries;
    private final TurnMetrics turnMetrics;
    private final dev.kualu.web.moderation.ModerationService moderation;
    private final SemanticMemory semanticMemory;
    private final Executor compressExecutor;

    @Autowired
    public ChatService(
            SessionStore sessionStore,
            LlmSettingsService llmSettings,
            ObjectProvider<MemoryCompressor> compressors,
            List<AgentTool> tools,
            ObjectProvider<McpConnectionManager> mcpManagers,
            ObjectProvider<UserMcpRegistry> userMcpRegistries,
            TurnMetrics turnMetrics,
            dev.kualu.web.moderation.ModerationService moderation,
            SemanticMemory semanticMemory) {
        this(sessionStore, llmSettings, compressors, tools, mcpManagers, userMcpRegistries,
                turnMetrics, moderation, newDaemonExecutor(), semanticMemory);
    }

    /** Visible for tests: semantic memory off (keeps older call sites compiling). */
    ChatService(
            SessionStore sessionStore,
            LlmSettingsService llmSettings,
            ObjectProvider<MemoryCompressor> compressors,
            List<AgentTool> tools,
            ObjectProvider<McpConnectionManager> mcpManagers,
            ObjectProvider<UserMcpRegistry> userMcpRegistries,
            TurnMetrics turnMetrics,
            dev.kualu.web.moderation.ModerationService moderation,
            Executor compressExecutor) {
        this(sessionStore, llmSettings, compressors, tools, mcpManagers, userMcpRegistries,
                turnMetrics, moderation, compressExecutor, SemanticMemory.disabled());
    }

    /** Visible for tests: lets them run compression inline (e.g. {@code Runnable::run}). */
    ChatService(
            SessionStore sessionStore,
            LlmSettingsService llmSettings,
            ObjectProvider<MemoryCompressor> compressors,
            List<AgentTool> tools,
            ObjectProvider<McpConnectionManager> mcpManagers,
            ObjectProvider<UserMcpRegistry> userMcpRegistries,
            TurnMetrics turnMetrics,
            dev.kualu.web.moderation.ModerationService moderation,
            Executor compressExecutor,
            SemanticMemory semanticMemory) {
        this.sessionStore = sessionStore;
        this.llmSettings = llmSettings;
        this.compressor = compressors.getIfAvailable();
        this.staticTools = List.copyOf(tools);
        this.mcpManagers = mcpManagers;
        this.userMcpRegistries = userMcpRegistries;
        this.turnMetrics = turnMetrics;
        this.moderation = moderation;
        this.compressExecutor = compressExecutor;
        this.semanticMemory = semanticMemory;
    }

    private static ExecutorService newDaemonExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-compress");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (compressExecutor instanceof ExecutorService service) {
            service.shutdown();
        }
    }

    /**
     * One turn's tool view: static beans (exec) bound to the owner via
     * {@link AgentTool#forUser}, the owner's own MCP tools, and the
     * shared {@code mcp.json} tools — minus any shared server the owner redefines
     * under the same name. The owner's semantic-memory tools ride along when the
     * feature is on. Re-read every turn so tools can come and go.
     */
    private List<AgentTool> toolsFor(String owner) {
        List<AgentTool> all = new ArrayList<>(staticTools.size());
        for (AgentTool tool : staticTools) {
            all.add(tool.forUser(owner));
        }
        all.addAll(semanticMemory.toolsFor(owner));
        Set<String> overridden = Set.of();
        UserMcpRegistry registry = userMcpRegistries.getIfAvailable();
        if (registry != null) {
            UserMcpRegistry.UserTools userTools = registry.toolsFor(owner);
            all.addAll(userTools.tools());
            overridden = userTools.serverNames();
        }
        McpConnectionManager manager = mcpManagers.getIfAvailable();
        if (manager != null) {
            all.addAll(manager.toolsExcluding(overridden));
        }
        return all;
    }

    /**
     * Per-turn loop: the tool list is re-read from the owner's MCP managers on
     * run(), and the semantic-memory preamble (policy + {@code 【用户记忆】} block)
     * is rebuilt per request — request-only, never stored.
     */
    private ToolLoop toolLoop(String owner, LlmClient client, String userContent) {
        return new ToolLoop(client, () -> toolsFor(owner),
                () -> semanticMemory.preamble(owner, userContent));
    }

    public boolean isLlmConfigured() {
        return llmSettings.isConfigured();
    }

    /**
     * One turn, picking the upstream from the requested (provider, model) — see
     * {@link LlmSettingsService#clientFor}. Falls back to the owner's saved choice
     * and then to the primary provider's default model.
     */
    public String chat(Session session, String content, List<String> images, String providerId, String model) {
        LlmClient client = requireLlm(session.owner(), providerId, model);
        // ConversationMemory is not thread-safe; serialize turns per session.
        synchronized (session.memory()) {
            String effective = gateInput(session, content);
            try {
                // The tool loop commits the user turn, any tool rounds, and the reply.
                String reply = toolLoop(session.owner(), client, effective).run(session.memory(), effective, images);
                String finalReply = moderation.gateOutput(session.memory(), session.owner(), session.id(), reply);
                persistAppendedQuietly(session);
                scheduleCompress(session);
                return finalReply;
            } catch (LlmException ex) {
                throw new UpstreamLlmException(ex.getMessage(), ex);
            }
        }
    }

    public String chatStream(Session session, String content, List<String> images, String providerId, String model,
            Consumer<String> onDelta) {
        LlmClient client = requireLlm(session.owner(), providerId, model);
        synchronized (session.memory()) {
            String effective = gateInput(session, content);
            // Segments are checked (and masked) before they reach the client; a
            // BLOCK finding aborts the run with ModerationBlockedException.
            var gate = moderation.enabled()
                    ? moderation.segmentGate(onDelta, session.owner(), session.id())
                    : null;
            Consumer<String> sink = gate != null ? gate : onDelta;
            try {
                String reply = toolLoop(session.owner(), client, effective).run(session.memory(), effective, images, sink);
                if (gate != null) {
                    gate.finish();
                }
                String finalReply = moderation.gateOutput(session.memory(), session.owner(), session.id(), reply);
                persistAppendedQuietly(session);
                scheduleCompress(session);
                return finalReply;
            } catch (dev.kualu.llm.moderation.ModerationBlockedException ex) {
                // Segment abort: the tool loop already committed the partial reply,
                // so replace it with the standard refusal before persisting.
                moderation.applyOutputBlock(session.memory(), session.owner(), session.id(), ex.hit());
                persistAppendedQuietly(session);
                throw ex;
            } catch (RuntimeException ex) {
                // Mid-stream failure (client disconnect, emitter timeout, upstream cut):
                // the tool loop has already committed whatever happened, so the turn
                // survives instead of being lost.
                persistAppendedQuietly(session);
                throw ex instanceof LlmException llm
                        ? new UpstreamLlmException(llm.getMessage(), llm)
                        : ex;
            }
        }
    }

    /**
     * Input moderation under the session lock. On BLOCK the placeholder turn is
     * already in the memory; it is persisted before the exception propagates.
     */
    private String gateInput(Session session, String content) {
        try {
            return moderation.gateInput(session.memory(), session.owner(), session.id(), content);
        } catch (dev.kualu.llm.moderation.ModerationBlockedException ex) {
            persistAppendedQuietly(session);
            throw ex;
        }
    }

    public List<HistoryMessage> history(Session session) {
        synchronized (session.memory()) {
            return session.memory().messages().stream()
                    .filter(message -> "user".equals(message.role())
                            || ("assistant".equals(message.role()) && !message.content().isBlank()))
                    .map(message -> new HistoryMessage(message.role(), message.content(),
                            message.images() == null ? null : message.images().stream()
                                    .map(image -> toDisplayUrl(session.id(), image))
                                    .toList()))
                    .toList();
        }
    }

    /**
     * The unfiltered message log for debug exports: system prompts, compression
     * summaries, and tool traffic included. Unlike {@link #history}, images are
     * returned as stored and blank assistant turns are kept.
     */
    public List<RawMessage> rawHistory(Session session) {
        synchronized (session.memory()) {
            return session.memory().messages().stream()
                    .map(message -> new RawMessage(
                            message.role(),
                            message.content(),
                            message.toolCallId(),
                            message.toolCalls() == null ? null : message.toolCalls().stream()
                                    .map(call -> new RawToolCall(call.id(), call.name(), call.arguments()))
                                    .toList(),
                            message.images(),
                            message.usage()))
                    .toList();
        }
    }

    /** Stored image refs become fetchable URLs; data URIs and http(s) URLs pass through. */
    private static String toDisplayUrl(String sessionId, String image) {
        return ImageRef.isRef(image)
                ? "/api/v1/sessions/" + sessionId + "/images/" + ImageRef.imageIdOf(image)
                : image;
    }

    /**
     * Best-effort compression after the turn: never on the user's critical path.
     * Two stages: bulky tool results are digested first, and the conversation is
     * folded into the summary only when that is not enough. Each stage snapshots
     * its plan under the session lock, runs the summarisation call outside it,
     * and applies (plus a persist) under the lock again.
     */
    private void scheduleCompress(Session session) {
        if (compressor == null || !compressor.shouldCompress(session.memory())) {
            return;
        }
        compressExecutor.execute(() -> runCompression(session));
    }

    private void runCompression(Session session) {
        // The executor thread may have inherited the submitting turn's collector.
        TurnTimings.clear();
        TurnTimings timings = TurnTimings.begin();
        long startedAt = System.nanoTime();
        boolean fired = false;
        int summaryChars = 0;
        try {
            summaryChars += runToolDigest(session);
            if (summaryChars > 0 && !compressor.stillOverThreshold(session.memory())) {
                fired = true;
            } else {
                int foldChars = runFold(session);
                summaryChars += foldChars;
                fired = summaryChars > 0;
            }
        } catch (RuntimeException ex) {
            log.warn("async memory-compress failed for session={}; history left unchanged", session.id(), ex);
        } finally {
            TurnTimings.recordCompress((System.nanoTime() - startedAt) / 1_000_000, fired, summaryChars);
            turnMetrics.complete("compress", session.id(), timings);
            TurnTimings.clear();
        }
    }

    /** Stage 1: condense bulky tool results in place. Returns digest chars written (0 = skipped/failed). */
    private int runToolDigest(Session session) {
        try {
            MemoryCompressor.ToolDigestPlan plan;
            synchronized (session.memory()) {
                plan = compressor.prepareToolDigest(session.memory());
            }
            if (plan == null) {
                return 0;
            }
            Map<String, String> digests = compressor.summarizeToolDigest(plan);
            if (digests.isEmpty()) {
                return 0;
            }
            boolean applied;
            synchronized (session.memory()) {
                applied = compressor.applyToolDigest(session.memory(), plan, digests);
                if (applied) {
                    // Digests rewrite message contents in place: append-only
                    // persistence cannot express that, so rewrite the full list.
                    persistRewrittenQuietly(session);
                }
            }
            if (!applied) {
                return 0;
            }
            return digests.values().stream()
                    .mapToInt(d -> Math.min(d.strip().length(), plan.maxDigestChars()))
                    .sum();
        } catch (RuntimeException ex) {
            log.warn("async tool-digest failed for session={}; tool results left unchanged", session.id(), ex);
            return 0;
        }
    }

    /** Stage 2: fold older turns into the summary. Returns summary chars written (0 = skipped/failed). */
    private int runFold(Session session) {
        MemoryCompressor.FoldPlan plan;
        synchronized (session.memory()) {
            plan = compressor.prepare(session.memory());
        }
        if (plan == null) {
            return 0;
        }
        String summary = compressor.summarize(plan);
        boolean fired;
        synchronized (session.memory()) {
            fired = compressor.apply(session.memory(), plan, summary);
            if (fired) {
                // The fold marker moved mid-history: positional appends are
                // invalid now, so this persists the full reshuffled list.
                persistRewrittenQuietly(session);
            }
        }
        return fired ? Math.min(summary == null ? 0 : summary.length(), plan.maxTokens()) : 0;
    }

    /**
     * Durability must not cost the user an answer that was already produced: if the
     * write fails the turn stays in process memory and the failure is loud in the logs
     * (and visible via {@code health.dbUp}), rather than surfaced as an error.
     * Appends are self-healing — the next persist writes whatever suffix is missing.
     */
    private void persistAppendedQuietly(Session session) {
        try {
            ensureTitle(session);
            sessionStore.appendMessages(session);
        } catch (RuntimeException ex) {
            log.warn("session-persist failed for session={}; the turn is kept in memory but is not durable",
                    session.id(), ex);
        }
    }

    private void persistRewrittenQuietly(Session session) {
        try {
            ensureTitle(session);
            sessionStore.replaceMessages(session);
        } catch (RuntimeException ex) {
            log.warn("session-rewrite failed for session={}; stored history keeps the pre-fold order",
                    session.id(), ex);
        }
    }

    /** Derives the listing title from the first user message exactly once per session. */
    private void ensureTitle(Session session) {
        if (session.title() != null) {
            return;
        }
        synchronized (session.memory()) {
            if (session.title() != null) {
                return;
            }
            session.memory().messages().stream()
                    .filter(message -> "user".equals(message.role()) && !message.content().isBlank())
                    .findFirst()
                    .ifPresent(message -> session.setTitle(deriveTitle(message.content())));
        }
    }

    private static String deriveTitle(String firstUserMessage) {
        String oneLine = firstUserMessage.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= TITLE_MAX_CHARS ? oneLine : oneLine.substring(0, TITLE_MAX_CHARS);
    }

    /**
     * The client for this turn: the requested (provider, model) when still on offer,
     * else the owner's saved choice, else the primary's default. Admin provider
     * edits take effect without a restart.
     */
    private LlmClient requireLlm(String owner, String providerId, String model) {
        LlmClient client = llmSettings.clientFor(owner, providerId, model);
        if (client == null) {
            throw new LlmNotConfiguredException("LLM is not configured");
        }
        return client;
    }
}
