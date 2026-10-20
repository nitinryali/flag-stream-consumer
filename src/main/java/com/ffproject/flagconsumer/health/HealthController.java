package com.ffproject.flagconsumer.health;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import com.ffproject.flagconsumer.flag.FlagStore;
import com.ffproject.flagconsumer.snapshot.SnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * One endpoint answering "is the stream alive, and what does the cache hold?".
 *
 * <p>Always returns {@code 200}: a degraded consumer still serves the last
 * known flags, which is more useful than a health check that fails the pod and
 * discards them. Inspect {@code status} and {@code redis} instead.</p>
 */
@RestController
public class HealthController {

    private final FlagStore store;
    private final ConsumerProperties properties;
    private final SubscriptionStatus subscriptionStatus;
    private final SnapshotService snapshotService;

    public HealthController(
            FlagStore store,
            ConsumerProperties properties,
            SubscriptionStatus subscriptionStatus,
            SnapshotService snapshotService) {
        this.store = store;
        this.properties = properties;
        this.subscriptionStatus = subscriptionStatus;
        this.snapshotService = snapshotService;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        boolean listening = subscriptionStatus.isListening();
        return new HealthResponse(
                listening ? "ok" : "degraded",
                listening ? "ready" : "down",
                properties.channel(),
                properties.tenantId(),
                store.tenantCount(),
                store.flagCount(),
                store.metrics(),
                store.lastEventAt(),
                snapshotService.status(),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    }

    public record HealthResponse(
            String status,
            String redis,
            String channel,
            String tenantId,
            int tenants,
            int flags,
            FlagStore.Metrics events,
            Instant lastEventAt,
            SnapshotService.Status snapshot,
            long uptimeSeconds) {
    }
}
