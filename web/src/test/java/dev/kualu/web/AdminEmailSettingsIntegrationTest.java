package dev.kualu.web;

import dev.kualu.web.email.EmailSettings;
import dev.kualu.web.email.EmailSettingsStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin SMTP settings: Aliyun-flavoured defaults, secret masking, and the
 * test-send endpoint failing cleanly while nothing is configured.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
        "agent.security.users[0].username=admin",
        "agent.security.users[0].password=password123",
        "agent.security.users[0].roles[0]=USER",
        "agent.security.users[0].roles[1]=ADMIN",
        "agent.security.users[1].username=bob",
        "agent.security.users[1].password=password123",
        "agent.security.users[1].roles[0]=USER"
})
@AutoConfigureMockMvc
class AdminEmailSettingsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmailSettingsStore settingsStore;

    @Test
    void settingsAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/email-settings"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/email-settings")
                        .header("Authorization", "Bearer " + login("bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    void defaultsMatchAliyunEnterpriseMail() throws Exception {
        mockMvc.perform(get("/api/v1/admin/email-settings")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("smtp.qiye.aliyun.com"))
                .andExpect(jsonPath("$.port").value(465))
                .andExpect(jsonPath("$.useSsl").value(true))
                .andExpect(jsonPath("$.password").value(""));
    }

    @Test
    void saveMasksPasswordAndStoresIt() throws Exception {
        String admin = login("admin");
        mockMvc.perform(put("/api/v1/admin/email-settings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"smtp.qiye.aliyun.com","port":465,"username":"bot@example.com",
                                 "password":"s3cret-pass","fromAddress":"","useSsl":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").value("••••••••"))
                .andExpect(content().string(not(containsString("s3cret-pass"))));

        // The real value is stored server-side; only the response is masked.
        assertThat(settingsStore.find().orElseThrow().password()).isEqualTo("s3cret-pass");

        mockMvc.perform(get("/api/v1/admin/email-settings")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bot@example.com"))
                .andExpect(jsonPath("$.password").value("••••••••"))
                .andExpect(content().string(not(containsString("s3cret-pass"))));
    }

    @Test
    void maskedUpdateKeepsStoredPassword() throws Exception {
        String admin = login("admin");
        settingsStore.save(new EmailSettings("smtp.qiye.aliyun.com", 465, "keep@example.com", "original", "", true));

        mockMvc.perform(put("/api/v1/admin/email-settings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"smtp.qiye.aliyun.com","port":465,"username":"keep@example.com",
                                 "password":"••••••••","fromAddress":"","useSsl":true}
                                """))
                .andExpect(status().isOk());
        assertThat(settingsStore.find().orElseThrow().password()).isEqualTo("original");
    }

    @Test
    void updateValidatesFields() throws Exception {
        String admin = login("admin");
        mockMvc.perform(put("/api/v1/admin/email-settings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"","port":465,"username":"x@example.com",
                                 "password":"p","fromAddress":"","useSsl":true}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/admin/email-settings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"smtp.qiye.aliyun.com","port":0,"username":"x@example.com",
                                 "password":"p","fromAddress":"","useSsl":true}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/admin/email-settings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"smtp.qiye.aliyun.com","port":465,"username":"",
                                 "password":"p","fromAddress":"","useSsl":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testSendWithoutConfigurationFailsCleanly() throws Exception {
        mockMvc.perform(post("/api/v1/admin/email-settings/test")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"someone@example.com\"}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void testSendRequiresRecipient() throws Exception {
        mockMvc.perform(post("/api/v1/admin/email-settings/test")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"\"}"))
                .andExpect(status().isBadRequest());
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
