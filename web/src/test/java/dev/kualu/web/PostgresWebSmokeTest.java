package dev.kualu.web;

import dev.kualu.llm.LlmClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end smoke over the real persistence path: Flyway runs through Spring,
 * the config-declared account is seeded at startup, and login/session/chat all hit
 * a real PostgreSQL (zonky embedded — no Docker).
 */
@SpringBootTest(properties = {
        "agent.store=postgres",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=alice",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.security.users[1].username=carol",
        "agent.security.users[1].password=password123",
        "agent.security.users[1].roles[0]=USER",
        "llm.base-url=http://localhost",
        "llm.api-key=test",
        "llm.model=test-model"
})
@AutoConfigureMockMvc
class PostgresWebSmokeTest {

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot start embedded PostgreSQL", ex);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // Loopback + sslmode=disable is the one plaintext case PostgresConfig allows.
        registry.add("agent.db.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres") + "&sslmode=disable");
        registry.add("agent.db.username", () -> "postgres");
        registry.add("agent.db.password", () -> "postgres");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    @Test
    void login_createSession_chat_allWorkAgainstTheDatabase() throws Exception {
        when(llmClient.chat(anyList())).thenReturn("nice to meet you");

        String token = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String jwt = com.jayway.jsonpath.JsonPath.read(token, "$.token");

        String session = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemPrompt\":\"Be brief.\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = com.jayway.jsonpath.JsonPath.read(session, "$.id");

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("nice to meet you"));

        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("hello"));

        // Survives "restart": the row is in the database, not just in this request.
        mockMvc.perform(get("/api/v1/sessions")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sessionId))
                .andExpect(jsonPath("$[0].messageCount").value(3));
    }

    @Test
    void health_reportsTheDatabase() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dbUp").value(true));
    }

    @Test
    void unknownCredentials_areRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_isWrittenToTheDatabase() throws Exception {
        // carol is reserved for this test so alice's seeded password survives method order.
        String token = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jwt = com.jayway.jsonpath.JsonPath.read(token, "$.token");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"new-secret-99\"}"))
                .andExpect(status().isNoContent());

        // Verified through a fresh authentication, which reads agent.users.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"new-secret-99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }
}
