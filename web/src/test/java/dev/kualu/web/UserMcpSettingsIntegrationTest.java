package dev.kualu.web;

import dev.kualu.web.mcp.UserMcpServer;
import dev.kualu.web.mcp.UserMcpServerStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-user MCP settings: CRUD, secret masking, and owner isolation. The servers
 * named here are never actually reachable — a failed connection must not fail the API.
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
class UserMcpSettingsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMcpServerStore store;

    @Test
    void listRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/settings/mcp-servers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createMasksSecretsAndStoresThem() throws Exception {
        String token = login("alice");
        mockMvc.perform(post("/api/v1/settings/mcp-servers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"masked-one","transport":"http","url":"http://127.0.0.1:1/mcp",
                                 "headers":{"Authorization":"Bearer s3cret"},
                                 "env":{},"args":[],"enabled":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.headers.Authorization").value("••••••••"))
                .andExpect(content().string(not(containsString("s3cret"))));

        mockMvc.perform(get("/api/v1/settings/mcp-servers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("s3cret"))))
                .andExpect(content().string(containsString("••••••••")));

        // The real value is stored server-side; only the response is masked.
        UserMcpServer saved = store.find("alice", "masked-one").orElseThrow();
        assertThat(saved.headers()).containsEntry("Authorization", "Bearer s3cret");
    }

    @Test
    void maskedUpdateKeepsStoredValue() throws Exception {
        String token = login("alice");
        createStdio(token, "keep-one", "GITHUB_TOKEN", "original-secret");

        // The mask round-trips: the key is kept, the stored value survives.
        mockMvc.perform(put("/api/v1/settings/mcp-servers/keep-one")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"keep-one","transport":"stdio","command":"nope","args":[],
                                 "env":{"GITHUB_TOKEN":"••••••••"},"headers":{},"enabled":true}
                                """))
                .andExpect(status().isOk());
        assertThat(store.find("alice", "keep-one").orElseThrow().env())
                .containsEntry("GITHUB_TOKEN", "original-secret");

        // A masked value for a key that was never stored must not be accepted.
        mockMvc.perform(put("/api/v1/settings/mcp-servers/keep-one")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"keep-one","transport":"stdio","command":"nope","args":[],
                                 "env":{"NEVER_STORED":"••••••••"},"headers":{},"enabled":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyValueRemovesKey() throws Exception {
        String token = login("alice");
        mockMvc.perform(post("/api/v1/settings/mcp-servers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"drop-one","transport":"stdio","command":"nope","args":[],
                                 "env":{"KEEP":"v","DROP":"v"},"headers":{},"enabled":true}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/settings/mcp-servers/drop-one")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"drop-one","transport":"stdio","command":"nope","args":[],
                                 "env":{"KEEP":"••••••••","DROP":""},"headers":{},"enabled":true}
                                """))
                .andExpect(status().isOk());

        assertThat(store.find("alice", "drop-one").orElseThrow().env())
                .containsEntry("KEEP", "v")
                .doesNotContainKey("DROP");
    }

    @Test
    void ownerIsolation() throws Exception {
        String alice = login("alice");
        String bob = login("bob");
        createStdio(alice, "private-one", "TOKEN", "alice-only");

        mockMvc.perform(get("/api/v1/settings/mcp-servers")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(jsonPath("$[?(@.name=='private-one')]", hasSize(0)));

        mockMvc.perform(put("/api/v1/settings/mcp-servers/private-one")
                        .header("Authorization", "Bearer " + bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"private-one","transport":"stdio","command":"nope","args":[],
                                 "env":{},"headers":{},"enabled":true}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/settings/mcp-servers/private-one")
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteAndDuplicateAndValidation() throws Exception {
        String token = login("alice");
        createStdio(token, "lifecycle-one", "TOKEN", "v");

        mockMvc.perform(post("/api/v1/settings/mcp-servers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"lifecycle-one","transport":"stdio","command":"nope","args":[],
                                 "env":{},"headers":{},"enabled":true}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/settings/mcp-servers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"bad name!","transport":"stdio","command":"nope","args":[],
                                 "env":{},"headers":{},"enabled":true}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/settings/mcp-servers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"no-command","transport":"stdio","args":[],
                                 "env":{},"headers":{},"enabled":true}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/settings/mcp-servers/lifecycle-one")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/settings/mcp-servers/lifecycle-one")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private void createStdio(String token, String name, String envKey, String envValue) throws Exception {
        mockMvc.perform(post("/api/v1/settings/mcp-servers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","transport":"stdio","command":"definitely-not-a-command","args":[],
                                 "env":{"%s":"%s"},"headers":{},"enabled":true}
                                """.formatted(name, envKey, envValue)))
                .andExpect(status().isCreated());
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
