package dev.kualu.web.memory;

import dev.kualu.llm.AgentTool;
import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolsTest {

    private MemoryStore store;
    private List<AgentTool> tools;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        tools = MemoryTools.forOwner("ada", store);
    }

    private String call(String toolName, String argsJson) {
        return tools.stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst()
                .orElseThrow()
                .execute(argsJson);
    }

    // ---- memory_save ----

    @Test
    void save_cleanFact_isStored() {
        String result = call("memory_save", "{\"kind\":\"fact\",\"content\":\"服务部署在 K8s\"}");

        assertTrue(result.contains("已记住"), result);
        List<MemoryEntry> entries = store.listEntries("ada");
        assertEquals(1, entries.size());
        assertEquals("服务部署在 K8s", entries.get(0).content());
        assertEquals(MemoryEntry.Kind.FACT, entries.get(0).kind());
    }

    @Test
    void save_pii_isRefusedAndNotStored() {
        String result = call("memory_save", "{\"kind\":\"fact\",\"content\":\"手机号 13812345678\"}");

        assertTrue(result.contains("隐私策略"), result);
        assertTrue(store.listEntries("ada").isEmpty(), "PII must never be stored");
        assertFalse(result.contains("13812345678"), "the refusal must not echo the identifier");
    }

    @Test
    void save_withId_updatesContent() {
        call("memory_save", "{\"kind\":\"fact\",\"content\":\"日志看 Loki\"}");
        MemoryEntry saved = store.listEntries("ada").get(0);

        String result = call("memory_save",
                "{\"kind\":\"preference\",\"content\":\"日志看 VictoriaMetrics\",\"id\":\"" + saved.id() + "\"}");

        assertTrue(result.contains("已更新"), result);
        List<MemoryEntry> entries = store.listEntries("ada");
        assertEquals(1, entries.size());
        assertEquals("日志看 VictoriaMetrics", entries.get(0).content());
        assertEquals(MemoryEntry.Kind.FACT, entries.get(0).kind(), "kind is immutable on update");
    }

    @Test
    void save_badKind_returnsErrorText() {
        String result = call("memory_save", "{\"kind\":\"nonsense\",\"content\":\"x\"}");

        assertTrue(result.contains("保存失败"), result);
        assertTrue(store.listEntries("ada").isEmpty());
    }

    @Test
    void save_overlongContent_isRejected() {
        String result = call("memory_save", "{\"kind\":\"fact\",\"content\":\"" + "长".repeat(501) + "\"}");

        assertTrue(result.contains("保存失败"), result);
        assertTrue(store.listEntries("ada").isEmpty());
    }

    @Test
    void save_invalidJson_returnsErrorText() {
        String result = call("memory_save", "not json");

        assertTrue(result.contains("保存失败"), result);
    }

    // ---- memory_forget ----

    @Test
    void forget_byId_deletesEntry() {
        call("memory_save", "{\"kind\":\"fact\",\"content\":\"服务部署在 K8s\"}");
        MemoryEntry saved = store.listEntries("ada").get(0);

        String result = call("memory_forget", "{\"id\":\"" + saved.id() + "\"}");

        assertTrue(result.contains("已删除"), result);
        assertTrue(store.listEntries("ada").isEmpty());
    }

    @Test
    void forget_byQuery_deletesMatches() {
        call("memory_save", "{\"kind\":\"fact\",\"content\":\"日志看 Loki\"}");
        call("memory_save", "{\"kind\":\"fact\",\"content\":\"服务部署在 K8s\"}");

        String result = call("memory_forget", "{\"query\":\"Loki\"}");

        assertTrue(result.contains("已删除 1 条"), result);
        assertEquals(1, store.listEntries("ada").size());
        assertEquals("服务部署在 K8s", store.listEntries("ada").get(0).content());
    }

    @Test
    void forget_missingQuery_returnsErrorText() {
        String result = call("memory_forget", "{}");

        assertTrue(result.contains("保存失败") || result.contains("删除失败"), result);
    }

    // ---- profile_set ----

    @Test
    void profileSet_nickname_isStored() {
        String result = call("profile_set", "{\"field\":\"nickname\",\"value\":\"Jason\"}");

        assertTrue(result.contains("已更新档案"), result);
        assertEquals("Jason", store.findProfile("ada").orElseThrow().nickname());
    }

    @Test
    void profileSet_city_isStored() {
        call("profile_set", "{\"field\":\"city\",\"value\":\"杭州\"}");

        assertEquals("杭州", store.findProfile("ada").orElseThrow().city());
    }

    @Test
    void profileSet_cityWithDistrict_isRefused() {
        String result = call("profile_set", "{\"field\":\"city\",\"value\":\"杭州西湖区\"}");

        assertTrue(result.contains("市级") || result.contains("隐私策略"), result);
        assertTrue(store.findProfile("ada").isEmpty(), "district-level city must not be stored");
    }

    @Test
    void profileSet_cityWithTownOrStreet_isRefused() {
        assertTrue(call("profile_set", "{\"field\":\"city\",\"value\":\"良渚街道\"}").contains("市级"));
        assertTrue(call("profile_set", "{\"field\":\"city\",\"value\":\"长安镇\"}").contains("市级"));
        assertTrue(call("profile_set", "{\"field\":\"city\",\"value\":\"文一西路\"}").contains("市级"));
        assertTrue(store.findProfile("ada").isEmpty());
    }

    @Test
    void save_credentialWithoutDelimiter_isRefused() {
        String result = call("memory_save", "{\"kind\":\"fact\",\"content\":\"我的密码是abc123\"}");

        assertTrue(result.contains("隐私策略"), result);
        assertTrue(store.listEntries("ada").isEmpty());
    }

    @Test
    void profileSet_piiValue_isRefused() {
        String result = call("profile_set", "{\"field\":\"nickname\",\"value\":\"13812345678\"}");

        assertTrue(result.contains("隐私策略"), result);
        assertTrue(store.findProfile("ada").isEmpty());
    }

    @Test
    void profileSet_gender_parsesAndKeepsOtherFields() {
        call("profile_set", "{\"field\":\"nickname\",\"value\":\"Jason\"}");
        call("profile_set", "{\"field\":\"gender\",\"value\":\"女\"}");
        call("profile_set", "{\"field\":\"city\",\"value\":\"杭州\"}");

        UserProfile profile = store.findProfile("ada").orElseThrow();
        assertEquals("Jason", profile.nickname());
        assertEquals(UserProfile.Gender.FEMALE, profile.gender());
        assertEquals("杭州", profile.city());
    }

    @Test
    void profileSet_badGender_returnsErrorText() {
        String result = call("profile_set", "{\"field\":\"gender\",\"value\":\"其他\"}");

        assertTrue(result.contains("保存失败"), result);
        assertTrue(store.findProfile("ada").isEmpty());
    }

    // ---- owner scoping ----

    @Test
    void tools_areScopedToTheirOwner() {
        List<AgentTool> bobTools = MemoryTools.forOwner("bob", store);
        bobTools.stream()
                .filter(tool -> tool.name().equals("memory_save"))
                .findFirst()
                .orElseThrow()
                .execute("{\"kind\":\"fact\",\"content\":\"bob 的事实\"}");

        assertTrue(store.listEntries("ada").isEmpty());
        assertEquals(1, store.listEntries("bob").size());
    }
}
