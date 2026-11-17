package dev.kualu.web.moderation;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.moderation.Category;
import dev.kualu.llm.moderation.ContentModerator;
import dev.kualu.llm.moderation.Direction;
import dev.kualu.llm.moderation.LexiconModerator;
import dev.kualu.llm.moderation.LlmModerator;
import dev.kualu.llm.moderation.Mode;
import dev.kualu.llm.moderation.ModerationBlockedException;
import dev.kualu.llm.moderation.ModerationHit;
import dev.kualu.llm.moderation.ModerationMessages;
import dev.kualu.llm.moderation.ModerationPolicy;
import dev.kualu.llm.moderation.NormalizedText;
import dev.kualu.llm.moderation.SegmentGate;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.web.llm.LlmSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Web-side moderation pipeline: the llm-core {@link ContentModerator} plus
 * audit recording. Input and output gates decide the effective text (original,
 * masked, or refusal) and leave one audit row per finding.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final ModerationProperties properties;
    private final ModerationEventStore store;
    private final ContentModerator moderator;
    private final ModerationPolicy policy;

    public ModerationService(
            ModerationProperties properties,
            ModerationEventStore store,
            LlmSettingsService llmSettings) {
        this.properties = properties;
        this.store = store;
        this.policy = new ModerationPolicy(properties.isFlagMode() ? Mode.FLAG : Mode.BLOCK);
        this.moderator = properties.enabled() ? build(llmSettings, properties, policy) : null;
        if (properties.enabled()) {
            log.info("moderation configured: mode={}, lexicon={}, llm.enabled={}, llm.model={}",
                    properties.mode(), properties.lexiconPath(),
                    properties.llm().enabled(), properties.llm().model());
            if (properties.isFlagMode()) {
                log.warn("moderation is in gray-release mode (flag): findings are audited but nothing is intercepted");
            }
        } else {
            log.info("moderation configured: enabled=false");
        }
    }

    private static ContentModerator build(LlmSettingsService llmSettings, ModerationProperties properties,
            ModerationPolicy policy) {
        LexiconModerator lexicon = new LexiconModerator(policy, properties.lexiconPath());
        LlmModerator classifier = null;
        if (properties.llm().enabled()) {
            classifier = new LlmModerator(
                    () -> {
                        LlmClient client = llmSettings.clientForModel(
                                properties.llm().provider(), properties.llm().model());
                        return client != null ? client : llmSettings.defaultClient();
                    },
                    properties.llm().timeout(),
                    0.7,
                    policy);
        }
        return new ContentModerator(lexicon, classifier, policy);
    }

    public boolean enabled() {
        return moderator != null;
    }

    /**
     * Input gate. On BLOCK: appends {@link ModerationMessages#BLOCKED_USER_PLACEHOLDER}
     * to {@code memory} (the raw text never enters history or the model) and throws.
     * On REDACT: returns the masked text. Otherwise returns {@code content} unchanged.
     * Every finding is audited first, whatever the disposition.
     */
    public String gateInput(ConversationMemory memory, String owner, String sessionId, String content) {
        if (moderator == null) {
            return content;
        }
        Optional<ModerationHit> hit = moderator.checkInput(content);
        if (hit.isEmpty()) {
            return content;
        }
        ModerationHit finding = hit.get();
        record(owner, sessionId, Direction.INPUT, finding, content);
        switch (finding.action()) {
            case BLOCK -> {
                memory.addUser(ModerationMessages.BLOCKED_USER_PLACEHOLDER);
                throw new ModerationBlockedException(finding, Direction.INPUT);
            }
            case REDACT -> {
                return moderator.redact(content, finding);
            }
            case FLAG -> {
                return content;
            }
        }
        return content;
    }

    /**
     * Output gate for a completed reply. Audits, then rewrites the memory's last
     * assistant turn to the effective text (refusal on BLOCK, masked on REDACT)
     * and returns it. Call under the session lock.
     */
    public String gateOutput(ConversationMemory memory, String owner, String sessionId, String reply) {
        if (moderator == null) {
            return reply;
        }
        Optional<ModerationHit> hit = moderator.checkOutput(reply);
        if (hit.isEmpty()) {
            return reply;
        }
        ModerationHit finding = hit.get();
        record(owner, sessionId, Direction.OUTPUT, finding, reply);
        return switch (finding.action()) {
            case BLOCK -> {
                rewriteLastAssistant(memory, ModerationMessages.OUTPUT_REFUSAL);
                yield ModerationMessages.OUTPUT_REFUSAL;
            }
            case REDACT -> {
                String masked = moderator.redact(reply, finding);
                rewriteLastAssistant(memory, masked);
                yield masked;
            }
            case FLAG -> reply;
        };
    }

    /**
     * Segment gate for a streamed reply: L1 per sentence before forwarding.
     * Findings are audited through {@code onFinding}; a BLOCK finding aborts the
     * stream with {@link ModerationBlockedException}.
     */
    public SegmentGate segmentGate(Consumer<String> sink, String owner, String sessionId) {
        Consumer<ModerationHit> onFinding = hit -> record(owner, sessionId, Direction.OUTPUT, hit, null);
        return new SegmentGate(moderator, sink, onFinding);
    }

    /**
     * After a segment-level BLOCK: ToolLoop has already committed the partial
     * reply; replace it with the standard refusal and audit the hit.
     */
    public void applyOutputBlock(ConversationMemory memory, String owner, String sessionId, ModerationHit hit) {
        record(owner, sessionId, Direction.OUTPUT, hit, null);
        rewriteLastAssistant(memory, ModerationMessages.OUTPUT_REFUSAL);
    }

    /** Writes one audit row; audit failure never changes the disposition. */
    public void record(String owner, String sessionId, Direction direction, ModerationHit hit, String text) {
        try {
            store.record(new ModerationEvent.New(
                    owner, sessionId, direction, hit.category(), hit.severity(), hit.action(),
                    hit.hasSpans() ? "LEXICON" : "LLM",
                    hit.excerpt(),
                    text == null ? null : sha256(NormalizedText.of(text).text()),
                    policy.needsReview(hit)));
        } catch (RuntimeException ex) {
            log.warn("moderation audit write failed for session={}; disposition unchanged", sessionId, ex);
        }
    }

    private static void rewriteLastAssistant(ConversationMemory memory, String newContent) {
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("assistant".equals(message.role())) {
                messages.set(i, new ChatMessage("assistant", newContent, message.toolCallId(), message.toolCalls()));
                memory.replaceAll(messages);
                return;
            }
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
