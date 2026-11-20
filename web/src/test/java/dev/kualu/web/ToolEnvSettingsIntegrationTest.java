package dev.kualu.web;

import dev.kualu.web.tools.UserToolEnvStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-user tool credentials: masking, key validation, and owner isolation.
 * {@code GH_TOKEN} is the archetypal entry — the exec tool injects it only into
 * the owner's own runs.
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
class ToolEnvSettingsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserToolEnvStore store;

    @Test
    void listRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/settings/tool-env"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void saveMasksAndStoresTheValue() throws Exception {
        String token = login("alice");
        mockMvc.perform(put("/api/v1/settings/tool-env/GH_TOKEN")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"ghp_verysecret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.GH_TOKEN").value("••••••••"))
                .andExpect(content().string(not(containsString("ghp_verysecret"))));

        mockMvc.perform(get("/api/v1/settings/tool-env")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.GH_TOKEN").value("••••••••"))
                .andExpect(content().string(not(containsString("ghp_verysecret"))));

        assertThat(store.find("alice", "GH_TOKEN")).contains("ghp_verysecret");
    }

    @Test
    void maskedValueKeepsTheStoredSecret() throws Exception {
        String token = login("alice");
        mockMvc.perform(put("/api/v1/settings/tool-env/GH_TOKEN")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"ghp_verysecret\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/settings/tool-env/GH_TOKEN")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"••••••••\"}"))
                .andExpect(status().isOk());

        assertThat(store.find("alice", "GH_TOKEN")).contains("ghp_verysecret");
    }

    @Test
    void maskedValueWithoutStoredEntryIsBadRequest() throws Exception {
        String token = login("alice");
        mockMvc.perform(put("/api/v1/settings/tool-env/NPM_TOKEN")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"••••••••\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managedConfigKeysAreRefused() throws Exception {
        String token = login("alice");
        for (String key : new String[] {"GH_CONFIG_DIR", "PATH", "LD_PRELOAD"}) {
            mockMvc.perform(put("/api/v1/settings/tool-env/" + key)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"/tmp/evil\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void ownersCannotSeeEachOthersEntries() throws Exception {
        String alice = login("alice");
        mockMvc.perform(put("/api/v1/settings/tool-env/GH_TOKEN")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"alice-token\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/settings/tool-env")
                        .header("Authorization", "Bearer " + login("bob")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("alice-token"))))
                .andExpect(content().string(not(containsString("GH_TOKEN"))));

        mockMvc.perform(delete("/api/v1/settings/tool-env/GH_TOKEN")
                        .header("Authorization", "Bearer " + login("bob")))
                .andExpect(status().isNotFound());
        assertThat(store.find("alice", "GH_TOKEN")).contains("alice-token");
    }

    @Test
    void deleteRemovesTheEntry() throws Exception {
        String token = login("alice");
        mockMvc.perform(put("/api/v1/settings/tool-env/GH_TOKEN")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"ghp_verysecret\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/settings/tool-env/GH_TOKEN")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(store.find("alice", "GH_TOKEN")).isEmpty();
    }

    private String login(String username) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
