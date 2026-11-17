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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Content-moderation contract (spec S2.4): blocked input is refused with 422 and
 * never enters history verbatim, redacted input is masked before the model sees
 * it, blocked output becomes the standard refusal, and the review API is
 * ADMIN-only. LLM classifier is off — these paths are the lexicon layer.
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
        "agent.moderation.enabled=true",
        "agent.moderation.mode=block",
        "agent.moderation.lexicon-path=classpath:moderation/test-lexicon.json",
        "agent.moderation.llm.enabled=false"
})
@AutoConfigureMockMvc
class ModerationIntegrationTest {

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
    void blockedInputReturns422AndNeverEntersHistory() throws Exception {
        String token = login("alice", "password123");
        String sessionId = createSession(token);

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"这里有暴力示例词\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("content_blocked"))
                .andExpect(jsonPath("$.message").value(containsString("内容不符合平台规范")));

        // The raw text is gone from history; a placeholder turn stands in for it.
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("【该消息未通过内容安全检查】"))
                .andExpect(content().string(not(containsString("暴力示例词"))));
    }

    @Test
    void redactedInputIsMaskedBeforeTheModelSeesIt() throws Exception {
        when(llmClient.chat(anyList())).thenReturn("好的");

        String token = login("alice", "password123");
        String sessionId = createSession(token);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你说脏话示例吗\"}"))
                .andExpect(status().isOk());

        // History keeps the masked text, never the raw term.
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("你说***吗"))
                .andExpect(content().string(not(containsString("脏话示例"))));
    }

    @Test
    void blockedOutputBecomesStandardRefusal() throws Exception {
        when(llmClient.chat(anyList())).thenReturn("回复里夹着暴力示例词，后面还有内容");

        String token = login("alice", "password123");
        String sessionId = createSession(token);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"随便聊聊\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(dev.kualu.llm.moderation.ModerationMessages.OUTPUT_REFUSAL));

        // The stored assistant turn is the refusal, not the violating reply.
        mockMvc.perform(get("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[1].content").value(dev.kualu.llm.moderation.ModerationMessages.OUTPUT_REFUSAL));
    }

    @Test
    void reviewApiIsAdminOnlyAndAcceptsVerdicts() throws Exception {
        String userToken = login("alice", "password123");
        mockMvc.perform(get("/api/v1/admin/moderation/events")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        String adminToken = login("boss", "password123");
        String sessionId = createSession(adminToken);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"这里有暴力示例词\"}"))
                .andExpect(status().isUnprocessableEntity());

        String events = mockMvc.perform(get("/api/v1/admin/moderation/events")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("VIOLENCE"))
                .andExpect(jsonPath("$[0].direction").value("INPUT"))
                .andExpect(jsonPath("$[0].reviewed").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(events, "$[0].id")).longValue();

        mockMvc.perform(post("/api/v1/admin/moderation/events/" + id + "/review")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"true positive\",\"override\":\"CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewed").value(true))
                .andExpect(jsonPath("$.reviewOverride").value("CONFIRM"));

        // Export routes the reviewed sample to the confirm bucket.
        mockMvc.perform(get("/api/v1/admin/moderation/events/export?kind=confirm")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reviewOverride").value("CONFIRM"));
        mockMvc.perform(get("/api/v1/admin/moderation/events/export?kind=none")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));

        // The audit row carries a masked excerpt, never the raw term.
        assertThat(audit.find(id).orElseThrow().excerpt()).doesNotContain("暴力示例词");
    }

    @Test
    void cleanInputPassesUntouched() throws Exception {
        when(llmClient.chat(anyList())).thenReturn("好的");

        String token = login("alice", "password123");
        String sessionId = createSession(token);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"今天天气不错\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("好的"));
    }

    @Test
    void blockedStreamSegmentEmitsModerationEventAndRefusal() throws Exception {
        when(llmClient.chatStream(anyList(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("安全的一句。");
            onDelta.accept("这里有暴力示例词。后面被掐断。");
            return "安全的一句。这里有暴力示例词。后面被掐断。";
        });

        String token = login("alice", "password123");
        String sessionId = createSession(token);
        String body = readSse(token, sessionId, "{\"content\":\"讲个故事\"}");

        // The clean segment goes out; the violating one is cut before broadcast.
        assertThat(body).contains("安全的一句。");
        assertThat(body).doesNotContain("暴力示例词");
        assertThat(body).contains("event:moderation");
        assertThat(body).contains("VIOLENCE");
        assertThat(body).contains("event:done");
        assertThat(body).contains(dev.kualu.llm.moderation.ModerationMessages.OUTPUT_REFUSAL);
    }

    private String readSse(String token, String sessionId, String jsonBody) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages/stream")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                        .content(jsonBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult(10_000);
        return new String(mvcResult.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String createSession(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
