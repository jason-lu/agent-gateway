package dev.kualu.web;

import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.UserProfile;
import dev.kualu.web.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The memory panel API: owner-scoped view/edit/delete/clear, and the same
 * hard-identifier gate on edits that the write tools enforce. New entries have
 * no create endpoint on purpose — they come from the in-conversation tools.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=alice",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.security.users[1].username=bob",
        "agent.security.users[1].password=password123",
        "agent.security.users[1].roles[0]=USER"
})
@AutoConfigureMockMvc
class MemoryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemoryStore store;

    /** The store bean is shared across tests in this class: start clean. */
    @BeforeEach
    void resetMemory() {
        store.clear("alice");
        store.clear("bob");
    }

    @Test
    void listRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/memories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_showsProfileAndEntries() throws Exception {
        store.saveProfile("alice", new UserProfile("Jason", UserProfile.Gender.FEMALE, "杭州"));
        store.saveEntry(new MemoryEntry(null, "alice", MemoryEntry.Kind.FACT,
                "服务部署在 K8s", MemoryEntry.Source.EXPLICIT, "session-1", null, null));
        String token = login("alice");

        mockMvc.perform(get("/api/v1/memories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.nickname").value("Jason"))
                .andExpect(jsonPath("$.profile.gender").value("female"))
                .andExpect(jsonPath("$.profile.city").value("杭州"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].content").value("服务部署在 K8s"))
                .andExpect(jsonPath("$.entries[0].kind").value("fact"))
                .andExpect(jsonPath("$.entries[0].source").value("explicit"));
    }

    @Test
    void list_isIsolatedPerOwner() throws Exception {
        store.saveEntry(new MemoryEntry(null, "alice", MemoryEntry.Kind.FACT,
                "alice 的事实", MemoryEntry.Source.EXPLICIT, null, null, null));
        String bobToken = login("bob");

        mockMvc.perform(get("/api/v1/memories")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.profile.nickname").value(nullValue()));
    }

    @Test
    void patch_updatesContent() throws Exception {
        MemoryEntry saved = store.saveEntry(new MemoryEntry(null, "alice", MemoryEntry.Kind.FACT,
                "日志看 Loki", MemoryEntry.Source.AUTO, null, null, null));
        String token = login("alice");

        mockMvc.perform(patch("/api/v1/memories/" + saved.id())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"日志看 VictoriaMetrics\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("日志看 VictoriaMetrics"))
                .andExpect(jsonPath("$.kind").value("fact"));
    }

    @Test
    void patch_rejectsHardIdentifiers() throws Exception {
        MemoryEntry saved = store.saveEntry(new MemoryEntry(null, "alice", MemoryEntry.Kind.FACT,
                "日志看 Loki", MemoryEntry.Source.AUTO, null, null, null));
        String token = login("alice");

        mockMvc.perform(patch("/api/v1/memories/" + saved.id())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"手机号 13812345678\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/memories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.entries[0].content").value("日志看 Loki"));
    }

    @Test
    void patch_otherOwnersEntry_isNotFound() throws Exception {
        MemoryEntry saved = store.saveEntry(new MemoryEntry(null, "alice", MemoryEntry.Kind.FACT,
                "alice 的事实", MemoryEntry.Source.EXPLICIT, null, null, null));
        String bobToken = login("bob");

        mockMvc.perform(patch("/api/v1/memories/" + saved.id())
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"改不动的\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_removesEntry() throws Exception {
        MemoryEntry saved = store.saveEntry(new MemoryEntry(null, "alice", MemoryEntry.Kind.FACT,
                "要删的", MemoryEntry.Source.EXPLICIT, null, null, null));
        String token = login("alice");

        mockMvc.perform(delete("/api/v1/memories/" + saved.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/memories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void clear_wipesProfileAndEntries() throws Exception {
        store.saveProfile("alice", new UserProfile("Jason", UserProfile.Gender.MALE, "杭州"));
        store.saveEntry(new MemoryEntry(null, "alice", MemoryEntry.Kind.FACT,
                "第一条", MemoryEntry.Source.EXPLICIT, null, null, null));
        store.saveEntry(new MemoryEntry(null, "bob", MemoryEntry.Kind.FACT,
                "bob 的", MemoryEntry.Source.EXPLICIT, null, null, null));
        String token = login("alice");

        mockMvc.perform(post("/api/v1/memories/clear")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/memories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.profile.nickname").value(nullValue()));
    }

    @Test
    void profilePut_writesAndClearsFields() throws Exception {
        String token = login("alice");

        mockMvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Jason\",\"gender\":\"male\",\"city\":\"杭州\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Jason"))
                .andExpect(jsonPath("$.city").value("杭州"));

        mockMvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"老王\",\"gender\":null,\"city\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("老王"))
                .andExpect(jsonPath("$.gender").value(nullValue()))
                .andExpect(jsonPath("$.city").value(nullValue()));
    }

    @Test
    void profilePut_rejectsDistrictLevelCity() throws Exception {
        String token = login("alice");

        mockMvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"杭州西湖区\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profilePut_rejectsPiiNickname() throws Exception {
        String token = login("alice");

        mockMvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"13812345678\"}"))
                .andExpect(status().isBadRequest());
    }

    private String login(String username) throws Exception {
        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.token");
    }
}
