package dev.kualu.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The raw message log ({@code /messages/raw}) is the debug JSON-export source —
 * system prompts, memory-compression summaries, and tool traffic included — so
 * it is ADMIN-only. The clean {@code /messages} transcript stays available to
 * the session owner.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=admin",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.security.users[0].roles[1]=ADMIN",
        "agent.security.users[1].username=alice",
        "agent.security.users[1].password=password123",
        "agent.security.users[1].roles[0]=USER"
})
@AutoConfigureMockMvc
class RawHistoryAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rawHistoryIsDeniedToPlainUsersAndAnonymousCallers() throws Exception {
        String alice = login("alice");
        String sessionId = createSession(alice);

        // The clean transcript stays available to the owner.
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());

        // The raw debug log does not.
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/messages/raw")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/messages/raw"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rawHistoryServesAdminSessions() throws Exception {
        String admin = login("admin");
        String sessionId = createSession(admin);

        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/messages/raw")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    private String createSession(String token) throws Exception {
        var result = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String login(String username) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
