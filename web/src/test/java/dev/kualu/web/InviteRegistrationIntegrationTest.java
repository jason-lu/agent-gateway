package dev.kualu.web;

import dev.kualu.web.invite.InviteCode;
import dev.kualu.web.invite.InviteCodeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The invite loop end to end: admin mints a code, registration consumes it
 * exactly once, and the new account can sign in. No real mail is sent — the
 * send path is exercised separately.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=admin",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.security.users[0].roles[1]=ADMIN"
})
@AutoConfigureMockMvc
class InviteRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InviteCodeStore inviteStore;

    @Test
    void registerRejectsUnknownOrMissingCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode":"NOSUCHCD","username":"someone","password":"password123"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode":"","username":"someone","password":"password123"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerValidatesPasswordLength() throws Exception {
        String code = createInvite("newbie@example.com", "新同事");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode":"%s","username":"newbie","password":"short"}
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
        // The failed attempt must not burn the code.
        assertThat(inviteStore.find(code).orElseThrow().used()).isFalse();
    }

    @Test
    void registerConsumesCodeAndSignsTheUserIn() throws Exception {
        String code = createInvite("carol@example.com", "carol");
        String token = register(code, "carol", "password123");
        assertThat(token).isNotBlank();

        // The account is a real USER and can call authenticated endpoints.
        mockMvc.perform(get("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // The code is spent.
        assertThat(inviteStore.find(code).orElseThrow().usedBy()).isEqualTo("carol");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode":"%s","username":"dave","password":"password123"}
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredCodeIsRejected() throws Exception {
        Instant now = Instant.now();
        inviteStore.insert(new InviteCode(
                "EXPIRED1", "old@example.com", "", "admin", now.minusSeconds(3600),
                now.minusSeconds(60), null, null, null));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode":"EXPIRED1","username":"late","password":"password123"}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(inviteStore.find("EXPIRED1").orElseThrow().used()).isFalse();
    }

    @Test
    void duplicateUsernameFailsWithoutBurningTheCode() throws Exception {
        String first = createInvite("one@example.com", null);
        register(first, "taken", "password123");

        String second = createInvite("two@example.com", null);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode":"%s","username":"taken","password":"password123"}
                                """.formatted(second)))
                .andExpect(status().isConflict());
        assertThat(inviteStore.find(second).orElseThrow().used()).isFalse();
    }

    @Test
    void adminManagesInviteCodes() throws Exception {
        String admin = login("admin");
        var created = mockMvc.perform(post("/api/v1/admin/invite-codes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"invited@example.com","note":"测试","validDays":3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("invited@example.com"))
                .andExpect(jsonPath("$.usedBy").value(emptyOrNullString()))
                .andReturn();
        String code = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.code");
        assertThat(code).hasSize(8);

        mockMvc.perform(get("/api/v1/admin/invite-codes")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().string(not(emptyOrNullString())));

        mockMvc.perform(delete("/api/v1/admin/invite-codes/" + code)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        assertThat(inviteStore.find(code)).isEmpty();
    }

    /** Admin mints a code through the API and returns it. */
    private String createInvite(String email, String note) throws Exception {
        String admin = login("admin");
        var created = mockMvc.perform(post("/api/v1/admin/invite-codes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","note":%s,"validDays":7}
                                """.formatted(email, note == null ? "null" : "\"" + note + "\"")))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.code");
    }

    private String register(String code, String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode":"%s","username":"%s","password":"%s"}
                                """.formatted(code, username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
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
