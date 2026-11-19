package dev.kualu.web;

import dev.kualu.llm.client.LlmClient;
import dev.kualu.web.moderation.ModerationEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gray-release mode ({@code agent.moderation.mode=flag}): findings are audited
 * but nothing is intercepted — the calibration path before switching to block.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.memory.semantic.enabled=false",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=alice",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.security.users[1].username=boss",
        "agent.security.users[1].password=password123",
        "agent.security.users[1].roles[0]=USER",
        "agent.security.users[1].roles[1]=ADMIN",
        "llm.base-url=http://localhost",
        "llm.api-key=test",
        "llm.model=test-model",
        "agent.tools.exec.enabled=false",
        "agent.tools.websearch.enabled=false",
        "agent.moderation.enabled=true",
        "agent.moderation.mode=flag",
        "agent.moderation.lexicon-path=classpath:moderation/test-lexicon.json",
        "agent.moderation.llm.enabled=false"
})
@AutoConfigureMockMvc
class ModerationFlagModeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ModerationEventStore audit;

    @MockitoBean
    private dev.kualu.web.llm.LlmSettingsService llmSettings;

    @MockitoBean
    private LlmClient llmClient;

    @BeforeEach
    void wireLlm() {
        when(llmSettings.clientFor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(llmClient);
        when(llmSettings.isConfigured()).thenReturn(true);
    }

    @Test
    void flagModeAuditsWithoutIntercepting() throws Exception {
        when(llmClient.chat(anyList())).thenReturn("好的");

        String token = login();
        String sessionId = createSession(token);

        // A HIGH finding that would be blocked in block mode sails through.
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"这里有暴力示例词\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("好的"));

        // ...but the finding is on record, flagged for review.
        String adminToken = loginBoss();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/admin/moderation/events")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("FLAG"))
                .andExpect(jsonPath("$[0].category").value("VIOLENCE"))
                .andExpect(jsonPath("$[0].needsReview").value(true));

        assertThat(audit.list(dev.kualu.web.moderation.ModerationEventStore.ModerationQuery
                        .pending(10, 0))).isNotEmpty();
    }

    private String login() throws Exception {
        return doLogin("alice");
    }

    private String loginBoss() throws Exception {
        return doLogin("boss");
    }

    private String doLogin(String username) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String createSession(String token) throws Exception {
        var result = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
