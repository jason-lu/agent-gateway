package dev.kualu.web;

import dev.kualu.web.auth.PasswordResetService;
import dev.kualu.web.auth.UserAccountStore;
import dev.kualu.web.invite.InviteCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The forgot-password loop over HTTP: the reset request never reveals whether an
 * account exists, and the mailed token exchanges for a new password that kills
 * every session. Mail is not delivered (no SMTP in tests) — the token is taken
 * from the service, which is what the mail body carries.
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
class PasswordResetIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InviteCodeService inviteCodes;

    @Autowired
    private UserAccountStore accountStore;

    @Autowired
    private PasswordResetService passwordResets;

    @Test
    void forgotPasswordIsAlwaysNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\"}"))
                .andExpect(status().isNoContent());

        register("fiona", "fiona@example.com");
        assertThat(accountStore.findEmail("fiona")).contains("fiona@example.com");
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"fiona\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void resetPasswordRejectsUnknownTokens() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"NOWHERE\",\"newPassword\":\"new-password1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPasswordSwapsThePasswordAndKillsTheSession() throws Exception {
        register("grace", "grace@example.com");
        String oldToken = login("grace", "password123");
        String raw = passwordResets.requestReset("grace").orElseThrow();

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"brand-new-1"}
                                """.formatted(raw)))
                .andExpect(status().isNoContent());

        // The old session is gone; the new password signs in.
        mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        assertThat(login("grace", "brand-new-1")).isNotBlank();

        // The token is spent.
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"again-2222"}
                                """.formatted(raw)))
                .andExpect(status().isBadRequest());
    }

    /** Mints an invite for the address and registers the account against it. */
    private void register(String username, String email) throws Exception {
        var invite = inviteCodes.create(email, null, "admin", 7);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inviteCode":"%s","username":"%s","password":"password123"}
                                """.formatted(invite.code(), username)))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
