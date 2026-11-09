package dev.kualu.web;

import dev.kualu.web.api.SettingsController;
import dev.kualu.web.llm.LlmProviderStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin LLM provider management: multiple connections with one protected primary,
 * masked API keys, and the promote-before-delete rule that keeps a default upstream
 * in place at all times.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=alice",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.security.users[1].username=boss",
        "agent.security.users[1].password=password123",
        "agent.security.users[1].roles[0]=USER",
        "agent.security.users[1].roles[1]=ADMIN"
})
@AutoConfigureMockMvc
class AdminLlmProvidersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LlmProviderStore store;

    @BeforeEach
    void reset() {
        store.findAll().forEach(provider -> store.delete(provider.id()));
    }

    @Test
    void requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/admin/llm-providers"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + login("alice")))
                .andExpect(status().isForbidden());
    }

    @Test
    void firstProviderIsPrimaryAndKeysStayMasked() throws Exception {
        String token = login("boss");
        mockMvc.perform(post("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"openai","baseUrl":"https://a.example.com/v1",
                                 "apiKey":"admin-secret","models":["gpt-4o","gpt-4o-mini"],"defaultModel":"gpt-4o"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.primary").value(true))
                .andExpect(jsonPath("$.apiKey").value(SettingsController.MASK))
                .andExpect(jsonPath("$.models[0]").value("gpt-4o"))
                .andExpect(content().string(not(containsString("admin-secret"))));

        mockMvc.perform(get("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("openai"))
                .andExpect(jsonPath("$[0].apiKey").value(SettingsController.MASK))
                .andExpect(content().string(not(containsString("admin-secret"))));

        // The real key is stored server-side; only the response is masked.
        assertThat(store.findAll().get(0).apiKey()).isEqualTo("admin-secret");
    }

    @Test
    void laterProvidersAreNotPrimary() throws Exception {
        String token = login("boss");
        createProvider(token, "openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        createProvider(token, "deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");

        mockMvc.perform(get("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("deepseek"))
                .andExpect(jsonPath("$[0].primary").value(false))
                .andExpect(jsonPath("$[1].name").value("openai"))
                .andExpect(jsonPath("$[1].primary").value(true));
    }

    @Test
    void maskAndBlankKeepTheStoredKeyWhileANewValueReplacesIt() throws Exception {
        String token = login("boss");
        String id = createProvider(token, "openai", "https://a.example.com/v1", "stored-secret",
                List.of("m1"), "m1").id();

        // The mask round-trips as "keep what is stored".
        mockMvc.perform(put("/api/v1/admin/llm-providers/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"openai","baseUrl":"https://a.example.com/v1",
                                 "apiKey":"%s","models":["m1"],"defaultModel":"m1"}
                                """.formatted(SettingsController.MASK)))
                .andExpect(status().isOk());
        assertThat(store.find(id).orElseThrow().apiKey()).isEqualTo("stored-secret");

        // So does a blank — the key is required, so clearing is not a thing.
        mockMvc.perform(put("/api/v1/admin/llm-providers/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"openai","baseUrl":"https://a.example.com/v1",
                                 "apiKey":"","models":["m1"],"defaultModel":"m1"}
                                """))
                .andExpect(status().isOk());
        assertThat(store.find(id).orElseThrow().apiKey()).isEqualTo("stored-secret");

        // A typed value replaces it.
        mockMvc.perform(put("/api/v1/admin/llm-providers/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"openai","baseUrl":"https://a.example.com/v1",
                                 "apiKey":"new-secret","models":["m1","m2"],"defaultModel":"m2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultModel").value("m2"))
                .andExpect(jsonPath("$.apiKey").value(SettingsController.MASK));
        assertThat(store.find(id).orElseThrow().apiKey()).isEqualTo("new-secret");
    }

    @Test
    void promoteMovesThePrimaryFlag() throws Exception {
        String token = login("boss");
        ProviderId first = createProvider(token, "openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        ProviderId second = createProvider(token, "deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");

        mockMvc.perform(post("/api/v1/admin/llm-providers/" + second.id() + "/primary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value(true));

        assertThat(store.find(first.id()).orElseThrow().primary()).isFalse();
        assertThat(store.find(second.id()).orElseThrow().primary()).isTrue();
    }

    @Test
    void primaryCannotBeDeletedUntilAnotherIsPromoted() throws Exception {
        String token = login("boss");
        ProviderId first = createProvider(token, "openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");
        ProviderId second = createProvider(token, "deepseek", "https://b.example.com/v1", "k2", List.of("d1"), "d1");

        mockMvc.perform(delete("/api/v1/admin/llm-providers/" + first.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/llm-providers/" + second.id() + "/primary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/llm-providers/" + first.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // The promoted provider is the only one left — and still cannot be deleted.
        mockMvc.perform(delete("/api/v1/admin/llm-providers/" + second.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateNameIsConflictAndBadInputIsBadRequest() throws Exception {
        String token = login("boss");
        createProvider(token, "openai", "https://a.example.com/v1", "k1", List.of("m1"), "m1");

        mockMvc.perform(post("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"openai","baseUrl":"https://b.example.com/v1",
                                 "apiKey":"k2","models":["d1"],"defaultModel":"d1"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"empty","baseUrl":"https://b.example.com/v1",
                                 "apiKey":"k2","models":[],"defaultModel":"d1"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"wrong-default","baseUrl":"https://b.example.com/v1",
                                 "apiKey":"k2","models":["d1"],"defaultModel":"nope"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private ProviderId createProvider(
            String token, String name, String baseUrl, String apiKey, List<String> models, String defaultModel)
            throws Exception {
        StringBuilder modelJson = new StringBuilder();
        for (String model : models) {
            if (modelJson.length() > 0) {
                modelJson.append(',');
            }
            modelJson.append('"').append(model).append('"');
        }
        var result = mockMvc.perform(post("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","baseUrl":"%s","apiKey":"%s","models":[%s],"defaultModel":"%s"}
                                """.formatted(name, baseUrl, apiKey, modelJson, defaultModel)))
                .andExpect(status().isCreated())
                .andReturn();
        return new ProviderId(com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id"));
    }

    private record ProviderId(String id) {
    }

    private String login(String username) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
