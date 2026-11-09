package dev.kualu.web;

import dev.kualu.web.llm.LlmProvider;
import dev.kualu.web.llm.LlmProviderStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The chat-window model picker: every (provider, model) on offer, the primary's
 * default as the fallback, and the caller's saved choice — per user, so one
 * account's pick never leaks into another's.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=alice",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.security.users[1].username=bob",
        "agent.security.users[1].password=password123",
        "agent.security.users[1].roles[0]=USER",
        "agent.security.users[2].username=boss",
        "agent.security.users[2].password=password123",
        "agent.security.users[2].roles[0]=USER",
        "agent.security.users[2].roles[1]=ADMIN"
})
@AutoConfigureMockMvc
class UserLlmModelIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LlmProviderStore store;

    private String primaryId;
    private String otherId;

    @BeforeEach
    void reset() {
        store.findAll().forEach(provider -> store.delete(provider.id()));
        primaryId = save("openai", List.of("gpt-4o", "gpt-4o-mini"), "gpt-4o", true);
        otherId = save("deepseek", List.of("deepseek-chat"), "deepseek-chat", false);
    }

    @Test
    void catalogNeedsAUser() throws Exception {
        mockMvc.perform(get("/api/v1/llm/models"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/llm/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"x\",\"model\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void catalogListsEveryPairWithTheDefaultSelection() throws Exception {
        mockMvc.perform(get("/api/v1/llm/models")
                        .header("Authorization", "Bearer " + login("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", hasSize(3)))
                .andExpect(jsonPath("$.defaultChoice.providerId").value(primaryId))
                .andExpect(jsonPath("$.defaultChoice.model").value("gpt-4o"))
                .andExpect(jsonPath("$.selected.providerId").value(primaryId))
                .andExpect(jsonPath("$.selected.model").value("gpt-4o"));
    }

    @Test
    void choosingAModelPersistsItPerUser() throws Exception {
        String alice = login("alice");
        String bob = login("bob");

        mockMvc.perform(put("/api/v1/llm/model")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerId":"%s","model":"deepseek-chat"}
                                """.formatted(otherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(otherId))
                .andExpect(jsonPath("$.model").value("deepseek-chat"));

        mockMvc.perform(get("/api/v1/llm/models")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(jsonPath("$.selected.providerId").value(otherId))
                .andExpect(jsonPath("$.selected.model").value("deepseek-chat"));

        // Another account still sees the deployment default.
        mockMvc.perform(get("/api/v1/llm/models")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(jsonPath("$.selected.providerId").value(primaryId))
                .andExpect(jsonPath("$.selected.model").value("gpt-4o"));
    }

    @Test
    void pickingAModelFromThePrimaryWorksToo() throws Exception {
        mockMvc.perform(put("/api/v1/llm/model")
                        .header("Authorization", "Bearer " + login("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerId":"%s","model":"gpt-4o-mini"}
                                """.formatted(primaryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"));
    }

    @Test
    void unknownPairsAreRejected() throws Exception {
        String alice = login("alice");
        mockMvc.perform(put("/api/v1/llm/model")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerId":"nope","model":"gpt-4o"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/llm/model")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerId":"%s","model":"not-offered"}
                                """.formatted(primaryId)))
                .andExpect(status().isBadRequest());
    }

    private String save(String name, List<String> models, String defaultModel, boolean primary) {
        LlmProvider provider = new LlmProvider(
                UUID.randomUUID().toString(), name, "https://" + name + ".example.com/v1",
                "key-" + name, models, defaultModel, primary);
        store.save(provider);
        return provider.id();
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
