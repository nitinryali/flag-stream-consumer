package com.ffproject.flagconsumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Consumer settings, bound from the {@code consumer.*} properties.
 *
 * <p>Everything has an environment-variable counterpart in
 * {@code application.properties}, so the same jar runs locally and in the
 * cloud with nothing but environment changes.</p>
 *
 * @param channel          Redis channel published to by the Flag Service
 * @param tenantId         the tenant this instance owns; also the default for
 *                         the HTTP API. Null means "accept every tenant".
 * @param flagServiceUrl   base URL of the Flag Service, or null to disable
 *                         snapshot sync
 * @param username         service account used for the snapshot login
 * @param password         password of that service account
 * @param snapshotRefreshMs interval between snapshot refreshes, in milliseconds
 */
@ConfigurationProperties(prefix = "consumer")
public record ConsumerProperties(
        String channel,
        String tenantId,
        String flagServiceUrl,
        String username,
        String password,
        long snapshotRefreshMs) {

    public ConsumerProperties {
        channel = hasText(channel) ? channel : "flag-updates";
        tenantId = trimToNull(tenantId);
        flagServiceUrl = trimToNull(flagServiceUrl);
        username = trimToNull(username);
        password = hasText(password) ? password : null;

        if (snapshotRefreshMs < 1_000) {
            throw new IllegalArgumentException("consumer.snapshot-refresh-ms must be at least 1000");
        }
        if (flagServiceUrl != null && (tenantId == null || username == null || password == null)) {
            throw new IllegalStateException(
                    "consumer.flag-service-url requires consumer.tenant-id, consumer.username and consumer.password");
        }
    }

    /**
     * Snapshot sync is opt-in: without a Flag Service URL the consumer works
     * from live events only.
     */
    public boolean snapshotEnabled() {
        return flagServiceUrl != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
