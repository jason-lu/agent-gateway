package dev.kualu.web;

import dev.kualu.web.files.FileArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tool artifact downloads: auth is the standard {@code /api/v1/**} rule (JWT via
 * header or {@code ?token=}), paths are sandboxed on the way in, files stream as
 * attachments.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=alice",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.files.dir=build/test-artifact-files"
})
@AutoConfigureMockMvc
class FileArtifactIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileArtifactStore store;

    @BeforeEach
    void seed() throws IOException {
        Path file = store.resolve("reports/q1.xlsx");
        Files.createDirectories(file.getParent());
        Files.write(file, "fake-xlsx-bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void downloadRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/files/reports/q1.xlsx"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadsWithBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/files/reports/q1.xlsx")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andExpect(content().string("fake-xlsx-bytes"))
                .andExpect(header().string("Content-Disposition", containsString("q1.xlsx")));
    }

    @Test
    void downloadsWithQueryToken() throws Exception {
        mockMvc.perform(get("/api/v1/files/reports/q1.xlsx").param("token", login()))
                .andExpect(status().isOk())
                .andExpect(content().string("fake-xlsx-bytes"));
    }

    @Test
    void missingFileIs404() throws Exception {
        mockMvc.perform(get("/api/v1/files/nope.xlsx")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isNotFound());
    }

    @Test
    void escapeAttemptIs400() throws Exception {
        mockMvc.perform(get("/api/v1/files/../secret.xlsx")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isBadRequest());
    }

    private String login() throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
