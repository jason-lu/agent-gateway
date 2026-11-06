package dev.kualu.web.chat;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.llm.metrics.TurnTimings;
import dev.kualu.llm.tools.ToolLoop;
import dev.kualu.mcp.McpConnectionManager;
import dev.kualu.web.api.ApiDtos.HistoryMessage;
import dev.kualu.web.config.LlmProperties;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    private final LlmClient llmClient;
    private final MemoryCompressor compressor;
    private final LlmProperties llmProperties;
    private final ToolLoop toolLoop;
    private final TurnMetrics turnMetrics;
    private final Executor compressExecutor;

    @Autowired
    public ChatService(
            SessionStore sessionStore,
            ObjectProvider<LlmClient> llmClients,
            ObjectProvider<MemoryCompressor> compressors,
            LlmProperties llmProperties,
            List<AgentTool> tools,
            ObjectProvider<McpConnectionManager> mcpManagers,
            TurnMetrics turnMetrics) {
        this(sessionStore, llmClients, compressors, llmProperties, tools, mcpManagers, turnMetrics,
                newDaemonExecutor());
    }

    /** Visible for tests: lets them run compression inline (e.g. {@code Runnable::run}). */
    ChatService(
            SessionStore sessionStore,
            ObjectProvider<LlmClient> llmClients,
            ObjectProvider<MemoryCompressor> compressors,
            LlmProperties llmProperties,
            List<AgentTool> tools,
            ObjectProvider<McpConnectionManager> mcpManagers,
            TurnMetrics turnMetrics,
            Executor compressExecutor) {
        this.sessionStore = sessionStore;
        this.llmClient = llmClients.getIfAvailable();
        this.compressor = compressors.getIfAvailable();
        this.llmProperties = llmProperties;
        this.turnMetrics = turnMetrics;
        this.toolLoop = this.llmClient == null ? null : new ToolLoop(this.llmClient, toolSupplier(tools, mcpManagers));
        this.compressExecutor = compressExecutor;
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

    /** Static beans (exec) plus the current MCP tool snapshot, per turn. */
    private static Supplier<List<AgentTool>> toolSupplier(
            List<AgentTool> staticTools, ObjectProvider<McpConnectionManager> mcpManagers) {
        return () -> {
            List<AgentTool> all = new ArrayList<>(staticTools);
            McpConnectionManager manager = mcpManagers.getIfAvailable();
            if (manager != null) {
                all.addAll(manager.tools());
            }
            return all;
        };
    }

    public boolean isLlmConfigured() {
        return llmProperties.isConfigured() && llmClient != null;
    }

    public String chat(Session session, String content, List<String> images) {
        requireLlm();
        // ConversationMemory is not thread-safe; serialize turns per session.
        synchronized (session.memory()) {
            try {
                // The tool loop commits the user turn, any tool rounds, and the reply.
                String reply = toolLoop.run(session.memory(), content, images);
                persistAppendedQuietly(session);
                scheduleCompress(session);
                return reply;
            } catch (LlmException ex) {
                throw new UpstreamLlmException(ex.getMessage(), ex);
            }
        }
    }

    public String chatStream(Session session, String content, List<String> images, Consumer<String> onDelta) {
        requireLlm();
        synchronized (session.memory()) {
            try {
                String reply = toolLoop.run(session.memory(), content, images, onDelta);
                persistAppendedQuietly(session);
                scheduleCompress(session);
                return reply;
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

    /** Stored image refs become fetchable URLs; data URIs and http(s) URLs pass through. */
    private static String toDisplayUrl(String sessionId, String image) {
        return ImageRef.isRef(image)
                ? "/api/v1/sessions/" + sessionId + "/images/" + ImageRef.imageIdOf(image)
                : image;
    }

    /**
     * Best-effort compression after the turn: never on the user's critical path.
     * The plan is snapshotted under the session lock, the summarisation call runs
     * outside it, and the fold (plus a persist) is applied under the lock again.
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
            MemoryCompressor.FoldPlan plan;
            synchronized (session.memory()) {
                plan = compressor.prepare(session.memory());
            }
            if (plan == null) {
                return;
            }
            String summary = compressor.summarize(plan);
            synchronized (session.memory()) {
                fired = compressor.apply(session.memory(), plan, summary);
                if (fired) {
                    // The fold marker moved mid-history: positional appends are
                    // invalid now, so this persists the full reshuffled list.
                    persistRewrittenQuietly(session);
                }
            }
            summaryChars = summary == null ? 0 : Math.min(summary.length(), plan.maxTokens());
        } catch (RuntimeException ex) {
            log.warn("async memory-compress failed for session={}; history left unchanged", session.id(), ex);
        } finally {
            TurnTimings.recordCompress((System.nanoTime() - startedAt) / 1_000_000, fired, summaryChars);
            turnMetrics.complete("compress", session.id(), timings);
            TurnTimings.clear();
        }
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

    private void requireLlm() {
        if (!isLlmConfigured()) {
            throw new LlmNotConfiguredException("LLM is not configured");
        }
    }
}
