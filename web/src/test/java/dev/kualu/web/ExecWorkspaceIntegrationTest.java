package dev.kualu.web;

import dev.kualu.llm.tools.ExecTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end per-user exec isolation: a token bound through the settings API must
 * reach only that owner's commands, and every run must happen inside that owner's
 * workspace with private CLI config directories.
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
class ExecWorkspaceIntegrationTest {

    private static final Path WORKSPACES = Path.of("build/test-workspaces");

    @DynamicPropertySource
    static void workspacesRoot(DynamicPropertyRegistry registry) {
        registry.add("agent.tools.exec.workspaces-root", () -> WORKSPACES.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExecTool execTool;

    @Test
    void tokenIsInjectedOnlyIntoItsOwnersRuns() throws Exception {
        String alice = login("alice");
        mockMvc.perform(put("/api/v1/settings/tool-env/GH_TOKEN")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"alice-token-abc\"}"))
                .andExpect(status().isOk());

        String aliceOutput = execTool.forUser("alice")
                .execute("{\"command\":\"echo token=%GH_TOKEN%\"}");
        String bobOutput = execTool.forUser("bob")
                .execute("{\"command\":\"echo token=%GH_TOKEN%\"}");

        assertThat(aliceOutput).contains("alice-token-abc");
        assertThat(bobOutput).doesNotContain("alice-token-abc");
    }

    @Test
    void runsLandInTheOwnersWorkspaceWithRedirectedConfigDirs() throws Exception {
        String output = execTool.forUser("alice")
                .execute("{\"command\":\"echo wd=%GH_CONFIG_DIR%\"}");

        assertThat(output.replace('\\', '/'))
                .contains(WORKSPACES.toString().replace('\\', '/'));
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
