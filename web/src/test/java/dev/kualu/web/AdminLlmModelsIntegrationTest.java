package dev.kualu.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.kualu.web.api.SettingsController;
import dev.kualu.web.llm.AdminLlmSettingsStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model discovery on the admin LLM settings: {@code POST /api/v1/admin/llm-settings/models}
 * probes the OpenAI-compatible {@code GET /models} of the connection the form holds —
 * draft values merged over the stored overrides, environment as fallback — and returns
 * the IDs for the admin dropdown. The model name itself is never required.
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
        "llm.api-key=env-secret",
        "llm.model=env-model"
})
@AutoConfigureMockMvc
class AdminLlmModelsIntegrationTest {

    private static final AtomicReference<String> envAuth = new AtomicReference<>();
    private static final AtomicReference<String> otherAuth = new AtomicReference<>();

    /** What the environment configuration points at. */
    private static final HttpServer ENV_UPSTREAM = modelsServer(List.of("env-model-a", "env-model-b"), envAuth);
    /** What a draft or stored override can point at instead. */
    private static final HttpServer OTHER_UPSTREAM = modelsServer(List.of("draft-model-x"), otherAuth);

    @DynamicPropertySource
    static void envConnection(DynamicPropertyRegistry registry) {
        registry.add("llm.base-url",
                () -> "http://127.0.0.1:" + ENV_UPSTREAM.getAddress().getPort() + "/v1");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminLlmSettingsStore store;

    @BeforeEach
    void reset() {
        store.delete();
        envAuth.set(null);
        otherAuth.set(null);
    }

    @AfterAll
    static void stopUpstreams() {
        ENV_UPSTREAM.stop(0);
        OTHER_UPSTREAM.stop(0);
    }

    @Test
    void requiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/admin/llm-settings/models"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/llm-settings/models")
                        .header("Authorization", "Bearer " + login("alice")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsModelsFromTheEffectiveConnection() throws Exception {
        mockMvc.perform(post("/api/v1/admin/llm-settings/models")
                        .header("Authorization", "Bearer " + login("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", contains("env-model-a", "env-model-b")));

        assertThat(envAuth.get()).isEqualTo("Bearer env-secret");
    }

    @Test
    void draftConnectionListsFromThatUpstream() throws Exception {
        mockMvc.perform(post("/api/v1/admin/llm-settings/models")
                        .header("Authorization", "Bearer " + login("boss"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"http://127.0.0.1:%d/v1","apiKey":"draft-key"}
                                """.formatted(OTHER_UPSTREAM.getAddress().getPort())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", contains("draft-model-x")));

        assertThat(otherAuth.get()).isEqualTo("Bearer draft-key");
        assertThat(envAuth.get()).isNull();
    }

    @Test
    void maskKeepsTheStoredKeyWhileBlankUrlFallsBackToEnv() throws Exception {
        String token = login("boss");
        putSettings(token, "http://127.0.0.1:" + OTHER_UPSTREAM.getAddress().getPort() + "/v1", "stored-secret");

        mockMvc.perform(post("/api/v1/admin/llm-settings/models")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"","apiKey":"%s"}
                                """.formatted(SettingsController.MASK)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", contains("env-model-a", "env-model-b")));

        // Blank cleared the stored base URL (environment wins) but the mask kept the key.
        assertThat(envAuth.get()).isEqualTo("Bearer stored-secret");
    }

    @Test
    void typedKeyReplacesTheStoredKey() throws Exception {
        String token = login("boss");
        putSettings(token, "http://127.0.0.1:" + OTHER_UPSTREAM.getAddress().getPort() + "/v1", "stored-secret");

        mockMvc.perform(post("/api/v1/admin/llm-settings/models")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"apiKey":"typed-key"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", contains("draft-model-x")));

        assertThat(otherAuth.get()).isEqualTo("Bearer typed-key");
    }

    @Test
    void unreachableUpstreamIsReportedAsBadGateway() throws Exception {
        HttpServer dead = modelsServer(List.of("unreachable"), new AtomicReference<>());
        int port = dead.getAddress().getPort();
        dead.stop(0);

        mockMvc.perform(post("/api/v1/admin/llm-settings/models")
                        .header("Authorization", "Bearer " + login("boss"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"http://127.0.0.1:%d/v1","apiKey":"k"}
                                """.formatted(port)))
                .andExpect(status().isBadGateway());
    }

    private void putSettings(String token, String baseUrl, String apiKey) throws Exception {
        mockMvc.perform(put("/api/v1/admin/llm-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"%s","apiKey":"%s","model":"stored-model"}
                                """.formatted(baseUrl, apiKey)))
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

    /** A minimal OpenAI-compatible {@code GET /models} upstream that records the bearer token. */
    private static HttpServer modelsServer(List<String> ids, AtomicReference<String> authCapture) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", exchange -> {
                authCapture.set(exchange.getRequestHeaders().getFirst("Authorization"));
                serve(exchange, ids);
            });
            server.createContext("/models", exchange -> {
                authCapture.set(exchange.getRequestHeaders().getFirst("Authorization"));
                serve(exchange, ids);
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void serve(HttpExchange exchange, List<String> ids) throws IOException {
        exchange.getRequestBody().readAllBytes();
        StringBuilder data = new StringBuilder();
        for (String id : ids) {
            if (data.length() > 0) {
                data.append(',');
            }
            data.append("{\"id\":\"").append(id).append("\"}");
        }
        byte[] body = ("{\"object\":\"list\",\"data\":[" + data + "]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
