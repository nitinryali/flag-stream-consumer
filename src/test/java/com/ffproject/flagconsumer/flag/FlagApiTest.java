package com.ffproject.flagconsumer.flag;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlagController.class)
@EnableConfigurationProperties(ConsumerProperties.class)
@Import(FlagStore.class)
class FlagApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FlagStore store;

    @BeforeEach
    void setUp() {
        store.apply(new FlagEvent("acme", "checkout-v2", true, 3L,
                "2026-10-18T12:00:00Z", FlagEvent.UPDATED));
        store.apply(new FlagEvent("acme", "search-v2", false, 1L,
                "2026-10-18T12:00:00Z", FlagEvent.CREATED));
        store.apply(new FlagEvent("acme", "removed", null, 1L,
                "2026-10-18T12:00:00Z", FlagEvent.DELETED));
    }

    @Test
    void listsLiveFlagsForATenant() throws Exception {
        mvc.perform(get("/v1/flags").param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.flags.length()").value(2))
                .andExpect(jsonPath("$.flags[0].name").value("checkout-v2"))
                .andExpect(jsonPath("$.flags[0].value").value(true))
                .andExpect(jsonPath("$.flags[1].name").value("search-v2"));
    }

    @Test
    void returnsOneFlagOrNotFound() throws Exception {
        mvc.perform(get("/v1/flags/checkout-v2").param("tenantId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("checkout-v2"))
                .andExpect(jsonPath("$.version").value(3));

        mvc.perform(get("/v1/flags/nope").param("tenantId", "acme"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("flag_not_found"));

        mvc.perform(get("/v1/flags/removed").param("tenantId", "acme"))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptsTheTenantHeader() throws Exception {
        mvc.perform(get("/v1/flags").header("X-Tenant-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"));
    }

    @Test
    void rejectsRequestsWithoutATenant() throws Exception {
        mvc.perform(get("/v1/flags"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("tenant_required"));
    }

    @Test
    void rejectsWrites() throws Exception {
        mvc.perform(post("/v1/flags").param("tenantId", "acme"))
                .andExpect(status().isMethodNotAllowed());
    }
}
