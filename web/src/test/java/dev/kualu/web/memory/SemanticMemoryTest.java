package dev.kualu.web.memory;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.MemoryPolicy;
import dev.kualu.llm.memory.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write → recall loop of semantic memory: tools persist, the preamble
 * recalls, the rescan purges. ChatService wiring lives in
 * {@code ChatServiceSemanticMemoryTest}.
 */
class SemanticMemoryTest {

    private MemoryStore store;
    private SemanticMemory memory;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        memory = new SemanticMemory(store, 8, true);
    }

    private String call(String owner, String toolName, String argsJson) {
        return memory.toolsFor(owner).stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst()
                .orElseThrow()
                .execute(argsJson);
    }

    // ---- write → recall ----

    @Test
    void savedFact_isRecalledInPreamble() {
        call("ada", "memory_save", "{\"kind\":\"fact\",\"content\":\"服务部署在 K8s\"}");

        List<ChatMessage> preamble = memory.preamble("ada", "帮我写脚本");

        assertTrue(preamble.get(0).content().contains(MemoryPolicy.TEXT.substring(0, 20)),
                "policy leads the preamble");
        String block = preamble.get(1).content();
        assertTrue(block.startsWith("【用户记忆】"), block);
        assertTrue(block.contains("- 事实：服务部署在 K8s"), block);
    }

    @Test
    void recall_isPerOwner() {
        call("ada", "memory_save", "{\"kind\":\"fact\",\"content\":\"服务部署在 K8s\"}");

        List<ChatMessage> bobPreamble = memory.preamble("bob", "hi");
        assertEquals(1, bobPreamble.size(), "bob has policy but no memory block");
        assertFalse(bobPreamble.get(0).content().contains("K8s"));
    }

    @Test
    void profileIsRecalledInPreamble() {
        call("ada", "profile_set", "{\"field\":\"nickname\",\"value\":\"Jason\"}");
        call("ada", "profile_set", "{\"field\":\"city\",\"value\":\"杭州\"}");

        String block = memory.preamble("ada", "hi").get(1).content();

        assertTrue(block.contains("- 称呼：Jason"), block);
        assertTrue(block.contains("- 生活城市：杭州"), block);
    }

    @Test
    void forgotFact_disappearsFromPreamble() {
        call("ada", "memory_save", "{\"kind\":\"fact\",\"content\":\"日志看 Loki\"}");
        call("ada", "memory_forget", "{\"query\":\"Loki\"}");

        List<ChatMessage> preamble = memory.preamble("ada", "hi");

        assertEquals(1, preamble.size(), "only the policy remains");
    }

    // ---- US-15 rescan ----

    @Test
    void rescan_purgesLeakedEntryAndExcludesIt() {
        // Seeded behind the gate, as if an older version had written it.
        store.saveEntry(new MemoryEntry(null, "ada", MemoryEntry.Kind.FACT,
                "手机号 13812345678", MemoryEntry.Source.AUTO, null, null, null));
        store.saveEntry(new MemoryEntry(null, "ada", MemoryEntry.Kind.FACT,
                "服务部署在 K8s", MemoryEntry.Source.AUTO, null, null, null));

        List<ChatMessage> preamble = memory.preamble("ada", "hi");

        String block = preamble.get(1).content();
        assertFalse(block.contains("13812345678"), block);
        assertTrue(block.contains("K8s"), block);
        assertEquals(1, store.listEntries("ada").size(), "the leaked entry is hard-deleted");
        assertEquals("服务部署在 K8s", store.listEntries("ada").get(0).content());
    }

    @Test
    void rescan_purgesLeakedProfile() {
        store.saveProfile("ada", new UserProfile("13812345678", UserProfile.Gender.MALE, "杭州"));

        memory.preamble("ada", "hi");

        assertTrue(store.findProfile("ada").isEmpty(), "the leaked profile row is hard-deleted");
    }

    // ---- disabled ----

    @Test
    void disabled_offersNoToolsAndNoPreamble() {
        SemanticMemory off = SemanticMemory.disabled();

        assertTrue(off.toolsFor("ada").isEmpty());
        assertTrue(off.preamble("ada", "hi").isEmpty());
    }

    // ---- failure semantics ----

    @Test
    void assembleFailure_skipsBlockInsteadOfThrowing() {
        MemoryStore broken = new InMemoryMemoryStore() {
            @Override
            public java.util.List<MemoryEntry> listEntries(String owner) {
                throw new IllegalStateException("db down");
            }
        };
        SemanticMemory memory = new SemanticMemory(broken, 8, true);

        List<ChatMessage> preamble = memory.preamble("ada", "hi");

        assertEquals(1, preamble.size(), "policy only — the block is skipped");
    }
}
