package dev.kualu.web;

import dev.kualu.web.api.SettingsController;
import dev.kualu.web.llm.AdminLlmSettingsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin LLM upstream settings: admin overrides win per field over the environment
 * config, the API key is masked in responses, and the mask round-trips "keep".
 * Unset overrides fall back to the environment (llm.* / LLM_* variables).
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
        "agent.security.users[1].roles[1]=ADMIN",
        "llm.base-url=https://env.example.com/v1",
        "llm.api-key=env-secret",
        "llm.model=env-model"
})
@AutoConfigureMockMvc
class AdminLlmSettingsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminLlmSettingsStore store;

    @BeforeEach
    void reset() {
        store.delete();
    }

    @Test
    void requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/admin/llm-settings"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + login("alice")))
                .andExpect(status().isForbidden());
    }

    @Test
    void envConfigIsEffectiveUntilOverridden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + login("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value(nullValue()))
                .andExpect(jsonPath("$.apiKey").value(nullValue()))
                .andExpect(jsonPath("$.effectiveBaseUrl").value("https://env.example.com/v1"))
                .andExpect(jsonPath("$.effectiveModel").value("env-model"))
                .andExpect(jsonPath("$.baseUrlSource").value("env"))
                .andExpect(jsonPath("$.apiKeySource").value("env"))
                .andExpect(jsonPath("$.modelSource").value("env"))
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void adminOverridesWinAndTheKeyIsMasked() throws Exception {
        String token = login("boss");
        putSettings(token, "https://admin.example.com/v1", "admin-secret", "admin-model");

        mockMvc.perform(get("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("https://admin.example.com/v1"))
                .andExpect(jsonPath("$.apiKey").value(SettingsController.MASK))
                .andExpect(jsonPath("$.model").value("admin-model"))
                .andExpect(jsonPath("$.effectiveBaseUrl").value("https://admin.example.com/v1"))
                .andExpect(jsonPath("$.effectiveModel").value("admin-model"))
                .andExpect(jsonPath("$.baseUrlSource").value("admin"))
                .andExpect(jsonPath("$.apiKeySource").value("admin"))
                .andExpect(jsonPath("$.modelSource").value("admin"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(content().string(not(containsString("admin-secret"))))
                .andExpect(content().string(not(containsString("env-secret"))));

        // The real key is stored server-side; only the response is masked.
        assertThat(store.find().orElseThrow().apiKey()).isEqualTo("admin-secret");
    }

    @Test
    void maskKeepsTheStoredKeyAndBlankClearsFields() throws Exception {
        String token = login("boss");
        putSettings(token, "https://admin.example.com/v1", "admin-secret", "admin-model");

        // The mask round-trips and absent fields keep their stored values.
        mockMvc.perform(put("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + SettingsController.MASK + "\"}"))
                .andExpect(status().isOk());
        assertThat(store.find().orElseThrow().apiKey()).isEqualTo("admin-secret");
        assertThat(store.find().orElseThrow().baseUrl()).isEqualTo("https://admin.example.com/v1");

        // A blank clears the override: the field falls back to the environment.
        mockMvc.perform(put("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"","apiKey":"","model":"admin-model"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrlSource").value("env"))
                .andExpect(jsonPath("$.apiKeySource").value("env"))
                .andExpect(jsonPath("$.modelSource").value("admin"))
                .andExpect(jsonPath("$.effectiveBaseUrl").value("https://env.example.com/v1"));

        assertThat(store.find().orElseThrow().apiKey()).isNull();
    }

    @Test
    void resetFallsBackToTheEnvironment() throws Exception {
        String token = login("boss");
        putSettings(token, "https://admin.example.com/v1", "admin-secret", "admin-model");

        mockMvc.perform(delete("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.baseUrl").value(nullValue()))
                .andExpect(jsonPath("$.baseUrlSource").value("env"))
                .andExpect(jsonPath("$.apiKeySource").value("env"))
                .andExpect(jsonPath("$.modelSource").value("env"))
                .andExpect(jsonPath("$.effectiveBaseUrl").value("https://env.example.com/v1"));
        assertThat(store.find()).isEmpty();
    }

    private void putSettings(String token, String baseUrl, String apiKey, String model) throws Exception {
        mockMvc.perform(put("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"%s","apiKey":"%s","model":"%s"}
                                """.formatted(baseUrl, apiKey, model)))
                .andExpect(status().isOk());
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
