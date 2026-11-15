package dev.kualu.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cache and 404 behaviour of the API surface: responses keep Spring Security's
 * no-store so a deploy is picked up on the next load, and an unknown path is a
 * plain JSON 404. (The static UI now lives behind nginx — see the frontend
 * deploy — so asset caching is configured there, not here.)
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234"
})
@AutoConfigureMockMvc
class CacheHeadersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiResponsesStayUncached() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void missingResourcesAreNotFoundNotInternalError() throws Exception {
        mockMvc.perform(get("/assets/missing.js"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("not found"));

        mockMvc.perform(get("/no-such-page"))
                .andExpect(status().isNotFound());
    }
}
