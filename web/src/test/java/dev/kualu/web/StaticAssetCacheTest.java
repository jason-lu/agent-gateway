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
 * Serving the static UI: fingerprinted {@code /assets/} files may be cached
 * forever while everything else keeps Spring Security's no-store so a deploy is
 * picked up on the next load, and a missing resource is a plain 404.
 */
@SpringBootTest(properties = {
        "agent.store=memory",
        "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234"
})
@AutoConfigureMockMvc
class StaticAssetCacheTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fingerprintedAssetsAreCachedForever() throws Exception {
        mockMvc.perform(get("/assets/cache-probe.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    void everythingElseStaysUncached() throws Exception {
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
