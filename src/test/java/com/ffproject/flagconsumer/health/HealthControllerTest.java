package com.ffproject.flagconsumer.health;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import com.ffproject.flagconsumer.flag.FlagEvent;
import com.ffproject.flagconsumer.flag.FlagStore;
import com.ffproject.flagconsumer.snapshot.FlagServiceClient;
import com.ffproject.flagconsumer.snapshot.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Built with standalone MockMvc and real collaborators: the health endpoint
 * only needs a boolean, a cache and a snapshot status, so no Spring context or
 * mocks are involved.
 */
class HealthControllerTest {

    private final ConsumerProperties properties = new ConsumerProperties(
            "flag-updates", null, null, null, null, 300_000);
    private final FlagStore store = new FlagStore(properties);
    private final SnapshotService snapshot =
            new SnapshotService(properties, new FlagServiceClient(properties), store);

    @BeforeEach
    void seed() {
        store.apply(new FlagEvent("acme", "checkout-v2", true, 2L,
                "2026-10-18T12:00:00Z", FlagEvent.UPDATED));
    }

    private MockMvc mvc(boolean listening) {
        return MockMvcBuilders
                .standaloneSetup(new HealthController(store, properties, () -> listening, snapshot))
                .build();
    }

    @Test
    void reportsOkWhileSubscribed() throws Exception {
        mvc(true).perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.redis").value("ready"))
                .andExpect(jsonPath("$.channel").value("flag-updates"))
                .andExpect(jsonPath("$.tenants").value(1))
                .andExpect(jsonPath("$.flags").value(1))
                .andExpect(jsonPath("$.events.applied").value(1))
                .andExpect(jsonPath("$.lastEventAt").exists())
                .andExpect(jsonPath("$.snapshot.enabled").value(false));
    }

    @Test
    void reportsDegradedWhenTheSubscriptionIsDown() throws Exception {
        mvc(false).perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.redis").value("down"))
                .andExpect(jsonPath("$.flags").value(1));
    }
}
