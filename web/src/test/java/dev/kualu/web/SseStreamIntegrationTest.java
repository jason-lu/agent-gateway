package dev.kualu.web;

import dev.kualu.llm.client.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint-level SSE contract test (S2.5): delta*, then done (or error).
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=alice",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "llm.base-url=http://localhost",
        "llm.api-key=test",
        "llm.model=test-model"
})
@AutoConfigureMockMvc
class SseStreamIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    @Test
    void streamEmitsDeltaThenDone() throws Exception {
        when(llmClient.completeStream(anyList(), anyList(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept("Hel");
            onDelta.accept("lo");
            return new dev.kualu.llm.AssistantTurn("Hello", java.util.List.of());
        });

        String token = login();
        String sessionId = createSession(token);
        String body = readSse(token, sessionId, "{\"content\":\"hi\"}");

        assertThat(body).contains("event:delta");
        assertThat(body).contains("Hel");
        assertThat(body).contains("lo");
        assertThat(body).contains("event:done");
        assertThat(body).contains("Hello");
    }

    @Test
    void streamEmitsErrorOnFailure() throws Exception {
        when(llmClient.completeStream(anyList(), anyList(), any()))
                .thenThrow(new dev.kualu.llm.LlmException("upstream boom", 500, "err"));

        String token = login();
        String sessionId = createSession(token);
        String body = readSse(token, sessionId, "{\"content\":\"hi\"}");

        assertThat(body).contains("event:error");
        assertThat(body).contains("upstream boom");
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private String createSession(String token) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private String readSse(String token, String sessionId, String jsonBody) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/messages/stream")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(jsonBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SseEmitter completes asynchronously on a worker thread.
        mvcResult.getAsyncResult(10_000);

        MockHttpServletResponse response = mvcResult.getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains("text/event-stream");
        return response.getContentAsString();
    }
}
