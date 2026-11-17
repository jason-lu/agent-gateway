package dev.kualu.web.memory;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.HardIdentifierDetector;
import dev.kualu.llm.memory.MemoryAssembler;
import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.MemoryPolicy;
import dev.kualu.llm.memory.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cross-session semantic memory for one deployment: per-owner tools on the
 * write path and the request-only {@code 【用户记忆】} preamble on the read
 * path. The preamble never enters {@code ConversationMemory}, so history and
 * persistence stay pure conversation.
 *
 * <p>The pre-injection rescan (US-15) runs here: anything the detector flags is
 * excluded from the block and hard-deleted from the store.
 */
public class SemanticMemory {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemory.class);

    private final MemoryStore store;
    private final MemoryAssembler assembler;
    private final boolean enabled;

    public SemanticMemory(MemoryStore store, int maxInject, boolean enabled) {
        this.store = store;
        // A disabled feature must never block startup, so an invalid max-inject
        // only matters when the assembler will actually run.
        this.assembler = new MemoryAssembler(enabled ? maxInject : Math.max(maxInject, 1));
        this.enabled = enabled;
    }

    /** For tests and for callers without a store: no tools, no preamble. */
    public static SemanticMemory disabled() {
        return new SemanticMemory(new InMemoryMemoryStore(), 8, false);
    }

    public boolean enabled() {
        return enabled;
    }

    /** The memory tools for one owner; empty when the feature is off. */
    public List<AgentTool> toolsFor(String owner) {
        return enabled ? MemoryTools.forOwner(owner, store) : List.of();
    }

    /**
     * Request-only system messages for one turn: the standing memory policy,
     * then the {@code 【用户记忆】} block when there is anything to say.
     * Assembly failures never block the turn — the block is skipped and logged.
     */
    public List<ChatMessage> preamble(String owner, String userContent) {
        if (!enabled) {
            return List.of();
        }
        List<ChatMessage> out = new ArrayList<>(2);
        out.add(ChatMessage.system(MemoryPolicy.TEXT));
        MemoryAssembler.Block block;
        try {
            UserProfile profile = store.findProfile(owner).orElse(null);
            List<MemoryEntry> entries = store.listEntries(owner);
            block = assembler.assemble(profile, entries, userContent);
        } catch (RuntimeException ex) {
            log.warn("semantic-memory[owner={}] assemble failed; memory block skipped", owner, ex);
            return out;
        }
        purge(owner, block);
        if (!block.text().isBlank()) {
            out.add(ChatMessage.system(block.text()));
        }
        return out;
    }

    /** US-15: hard-delete whatever the rescan flagged, loudly but without content. */
    private void purge(String owner, MemoryAssembler.Block block) {
        for (UUID id : block.purgeEntries()) {
            try {
                store.deleteEntry(owner, id);
                log.warn("semantic-memory[owner={}] rescan-cleaned entry id={}", owner, id);
            } catch (RuntimeException ex) {
                log.warn("semantic-memory[owner={}] rescan-cleanup failed for id={}", owner, id, ex);
            }
        }
        if (block.purgeProfile()) {
            try {
                store.deleteProfile(owner);
                log.warn("semantic-memory[owner={}] rescan-cleaned profile", owner);
            } catch (RuntimeException ex) {
                log.warn("semantic-memory[owner={}] profile rescan-cleanup failed", owner, ex);
            }
        }
    }
}
