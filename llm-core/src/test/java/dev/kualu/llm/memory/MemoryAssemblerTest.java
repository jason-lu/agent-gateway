package dev.kualu.llm.memory;

import dev.kualu.llm.memory.MemoryEntry.Kind;
import dev.kualu.llm.memory.MemoryEntry.Source;
import dev.kualu.llm.memory.UserProfile.Gender;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAssemblerTest {

    private static MemoryEntry entry(String content) {
        return entry(content, Kind.FACT);
    }

    private static MemoryEntry entry(String content, Kind kind) {
        return new MemoryEntry(UUID.randomUUID(), "ada", kind, content, Source.EXPLICIT,
                "session-1", Instant.now(), Instant.now());
    }

    @Test
    void profileAndEntries_assembleWithProfileFirst() {
        MemoryAssembler assembler = new MemoryAssembler(8);
        UserProfile profile = new UserProfile("Jason", Gender.MALE, "杭州");

        MemoryAssembler.Block block = assembler.assemble(profile,
                List.of(entry("服务部署在 K8s", Kind.FACT), entry("报告用中文", Kind.PREFERENCE)),
                "帮我写脚本");

        String[] lines = block.text().split("\n");
        assertEquals(MemoryAssembler.BLOCK_PREFIX, lines[0]);
        assertEquals("- 称呼：Jason", lines[1]);
        assertEquals("- 性别：男", lines[2]);
        assertEquals("- 生活城市：杭州", lines[3]);
        assertTrue(block.text().contains("- 事实：服务部署在 K8s"));
        assertTrue(block.text().contains("- 偏好：报告用中文"));
        assertTrue(block.purgeEntries().isEmpty());
        assertFalse(block.purgeProfile());
    }

    @Test
    void emptyInputs_produceEmptyText() {
        MemoryAssembler.Block block = new MemoryAssembler(8).assemble(null, List.of(), "hi");

        assertEquals("", block.text());
        assertTrue(block.purgeEntries().isEmpty());
    }

    @Test
    void unspecifiedGender_isOmitted() {
        MemoryAssembler.Block block = new MemoryAssembler(8)
                .assemble(new UserProfile("Jason", Gender.UNSPECIFIED, null), List.of(), "hi");

        assertTrue(block.text().contains("- 称呼：Jason"));
        assertFalse(block.text().contains("性别"));
        assertFalse(block.text().contains("生活城市"));
    }

    @Test
    void topK_truncatesToMaxInject() {
        MemoryAssembler assembler = new MemoryAssembler(2);
        List<MemoryEntry> entries = List.of(entry("第一条"), entry("第二条"), entry("第三条"));

        MemoryAssembler.Block block = assembler.assemble(null, entries, "无关问题");

        assertEquals(2, block.text().split("\n").length - 1, "header + 2 lines");
    }

    @Test
    void relevance_rankingPrefersOverlappingContent() {
        MemoryAssembler assembler = new MemoryAssembler(1);
        MemoryEntry unrelated = entry("我养了一只猫");
        MemoryEntry related = entry("服务部署在 Kubernetes 上");

        MemoryAssembler.Block block = assembler.assemble(null,
                List.of(unrelated, related), "帮我写 Kubernetes 部署脚本");

        assertTrue(block.text().contains("Kubernetes"), block.text());
        assertFalse(block.text().contains("猫"));
    }

    @Test
    void rescan_hit_excludesAndPurgesEntry() {
        MemoryAssembler assembler = new MemoryAssembler(8);
        MemoryEntry clean = entry("服务部署在 K8s");
        MemoryEntry leaked = entry("手机号 13812345678");

        MemoryAssembler.Block block = assembler.assemble(null, List.of(clean, leaked), "hi");

        assertTrue(block.text().contains("K8s"));
        assertFalse(block.text().contains("13812345678"));
        assertEquals(1, block.purgeEntries().size());
        assertEquals(leaked.id(), block.purgeEntries().get(0));
    }

    @Test
    void rescan_hitOnProfile_purgesProfile() {
        MemoryAssembler assembler = new MemoryAssembler(8);
        UserProfile profile = new UserProfile("13812345678", Gender.MALE, "杭州");

        MemoryAssembler.Block block = assembler.assemble(profile, List.of(), "hi");

        assertTrue(block.purgeProfile());
        assertEquals("", block.text());
    }

    @Test
    void maxInject_belowOne_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryAssembler(0));
    }
}
