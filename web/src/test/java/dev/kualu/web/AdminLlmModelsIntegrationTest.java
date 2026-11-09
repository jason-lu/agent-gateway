package dev.kualu.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.kualu.web.api.SettingsController;
import dev.kualu.web.llm.LlmProviderStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Model discovery for the admin provider form: {@code POST /api/v1/admin/llm-providers/models}
 * probes the OpenAI-compatible {@code GET /models} of the connection the form holds —
 * draft values, with {@code id} anchoring the merge so the mask keeps a stored key.
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
        "agent.security.users[1].roles[1]=ADMIN"
})
@AutoConfigureMockMvc
class AdminLlmModelsIntegrationTest {

    private static final AtomicReference<String> draftAuth = new AtomicReference<>();
    private static final AtomicReference<String> storedAuth = new AtomicReference<>();

    /** What a draft connection (the admin form typing fresh values) points at. */
    private static final HttpServer DRAFT_UPSTREAM = modelsServer(List.of("draft-model-x", "draft-model-y"), draftAuth);
    /** What a stored provider row points at. */
    private static final HttpServer STORED_UPSTREAM = modelsServer(List.of("stored-model-a"), storedAuth);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LlmProviderStore store;

    @BeforeEach
    void reset() {
        store.findAll().forEach(provider -> store.delete(provider.id()));
        draftAuth.set(null);
        storedAuth.set(null);
    }

    @AfterAll
    static void stopUpstreams() {
        DRAFT_UPSTREAM.stop(0);
        STORED_UPSTREAM.stop(0);
    }

    @Test
    void requiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/admin/llm-providers/models"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/llm-providers/models")
                        .header("Authorization", "Bearer " + login("alice")))
                .andExpect(status().isForbidden());
    }

    @Test
    void draftConnectionListsFromThatUpstream() throws Exception {
        mockMvc.perform(post("/api/v1/admin/llm-providers/models")
                        .header("Authorization", "Bearer " + login("boss"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"http://127.0.0.1:%d/v1","apiKey":"draft-key"}
                                """.formatted(port(DRAFT_UPSTREAM))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", contains("draft-model-x", "draft-model-y")));

        assertThat(draftAuth.get()).isEqualTo("Bearer draft-key");
    }

    @Test
    void maskAndBlankMergeOverTheStoredProvider() throws Exception {
        String token = login("boss");
        String id = createProvider(token);

        // The mask keeps the stored key; the blank base URL keeps the stored URL.
        mockMvc.perform(post("/api/v1/admin/llm-providers/models")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","baseUrl":"","apiKey":"%s"}
                                """.formatted(id, SettingsController.MASK)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", contains("stored-model-a")));

        assertThat(storedAuth.get()).isEqualTo("Bearer stored-secret");
        assertThat(draftAuth.get()).isNull();
    }

    @Test
    void typedKeyReplacesTheStoredKey() throws Exception {
        String token = login("boss");
        String id = createProvider(token);

        mockMvc.perform(post("/api/v1/admin/llm-providers/models")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"%s","apiKey":"typed-key"}
                                """.formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models", contains("stored-model-a")));

        assertThat(storedAuth.get()).isEqualTo("Bearer typed-key");
    }

    @Test
    void probeWithoutAnyConnectionIsServiceUnavailable() throws Exception {
        mockMvc.perform(post("/api/v1/admin/llm-providers/models")
                        .header("Authorization", "Bearer " + login("boss")))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/api/v1/admin/llm-providers/models")
                        .header("Authorization", "Bearer " + login("boss"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"https://half.example.com/v1"}
                                """))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unreachableUpstreamIsReportedAsBadGateway() throws Exception {
        HttpServer dead = modelsServer(List.of("unreachable"), new AtomicReference<>());
        int port = port(dead);
        dead.stop(0);

        mockMvc.perform(post("/api/v1/admin/llm-providers/models")
                        .header("Authorization", "Bearer " + login("boss"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"http://127.0.0.1:%d/v1","apiKey":"k"}
                                """.formatted(port)))
                .andExpect(status().isBadGateway());
    }

    private String createProvider(String token) throws Exception {
        var result = mockMvc.perform(post("/api/v1/admin/llm-providers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"stored","baseUrl":"http://127.0.0.1:%d/v1",
                                 "apiKey":"stored-secret","models":["stored-model-a"],"defaultModel":"stored-model-a"}
                                """.formatted(port(STORED_UPSTREAM))))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static int port(HttpServer server) {
        return server.getAddress().getPort();
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
