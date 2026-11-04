package dev.kualu.web.chat;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.LlmClient;
import dev.kualu.llm.LlmException;
import dev.kualu.llm.MemoryCompressor;
import dev.kualu.llm.ToolLoop;
import dev.kualu.web.api.ApiDtos.HistoryMessage;
import dev.kualu.web.config.LlmProperties;
import dev.kualu.web.session.Session;
import dev.kualu.web.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * Turns HTTP chat operations into llm-core calls and session updates.
 * Tool calls (the exec tool) are run by {@link ToolLoop} inside the turn.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final SessionStore sessionStore;
    private final LlmClient llmClient;
    private final MemoryCompressor compressor;
    private final LlmProperties llmProperties;
    private final ToolLoop toolLoop;

    public ChatService(
            SessionStore sessionStore,
            ObjectProvider<LlmClient> llmClients,
            ObjectProvider<MemoryCompressor> compressors,
            LlmProperties llmProperties,
            List<AgentTool> tools) {
        this.sessionStore = sessionStore;
        this.llmClient = llmClients.getIfAvailable();
        this.compressor = compressors.getIfAvailable();
        this.llmProperties = llmProperties;
        this.toolLoop = this.llmClient == null ? null : new ToolLoop(this.llmClient, tools);
    }

    public boolean isLlmConfigured() {
        return llmProperties.isConfigured() && llmClient != null;
    }

    public String chat(Session session, String content) {
        requireLlm();
        // ConversationMemory is not thread-safe; serialize turns per session.
        synchronized (session.memory()) {
            try {
                compress(session);
                // The tool loop commits the user turn, any tool rounds, and the reply.
                String reply = toolLoop.run(session.memory(), content);
                persistQuietly(session);
                return reply;
            } catch (LlmException ex) {
                throw new UpstreamLlmException(ex.getMessage(), ex);
            }
        }
    }

    public String chatStream(Session session, String content, Consumer<String> onDelta) {
        requireLlm();
        synchronized (session.memory()) {
            try {
                compress(session);
                String reply = toolLoop.run(session.memory(), content, onDelta);
                persistQuietly(session);
                return reply;
            } catch (RuntimeException ex) {
                // Mid-stream failure (client disconnect, emitter timeout, upstream cut):
                // the tool loop has already committed whatever happened, so the turn
                // survives instead of being lost.
                persistQuietly(session);
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
                    .map(message -> new HistoryMessage(message.role(), message.content()))
                    .toList();
        }
    }

    /** Best-effort compression before the payload is built; never blocks the turn. */
    private void compress(Session session) {
        if (compressor != null) {
            compressor.maybeCompress(session.memory(), "session=" + session.id());
        }
    }

    /**
     * Durability must not cost the user an answer that was already produced: if the
     * write fails the turn stays in process memory and the failure is loud in the logs
     * (and visible via {@code health.dbUp}), rather than surfaced as an error.
     */
    private void persistQuietly(Session session) {
        try {
            sessionStore.update(session);
        } catch (RuntimeException ex) {
            log.warn("session-persist failed for session={}; the turn is kept in memory but is not durable",
                    session.id(), ex);
        }
    }

    private void requireLlm() {
        if (!isLlmConfigured()) {
            throw new LlmNotConfiguredException("LLM is not configured");
        }
    }
}
