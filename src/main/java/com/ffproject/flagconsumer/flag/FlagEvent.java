package com.ffproject.flagconsumer.flag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Set;

/**
 * One message from the {@code flag-updates} channel.
 *
 * <p>Wire shape (published by {@code FlagEventPublisher}):</p>
 *
 * <pre>{@code
 * {"tenantId":"66f0acme","name":"checkout-v2","value":true,
 *  "version":4,"ts":"2026-10-18T12:34:56.789Z","event":"updated"}
 * }</pre>
 *
 * <p>Deletions carry {@code "value": null}. {@code version} is what lets a
 * consumer ignore out-of-order deliveries; {@code tenantId} lets it discard
 * events for tenants it does not own.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlagEvent(
        String tenantId,
        String name,
        Boolean value,
        Long version,
        String ts,
        String event) {

    public static final String CREATED = "created";
    public static final String UPDATED = "updated";
    public static final String DELETED = "deleted";

    private static final Set<String> EVENT_TYPES = Set.of(CREATED, UPDATED, DELETED);

    public boolean isDeleted() {
        return DELETED.equals(event);
    }

    /**
     * Rejects anything the Flag Service would never publish: a missing tenant
     * or name, an unknown event type, a non-numeric version, or a non-deletion
     * without a boolean value.
     */
    public boolean isValid() {
        return hasText(tenantId)
                && hasText(name)
                && version != null
                && version >= 0
                && event != null
                && EVENT_TYPES.contains(event)
                && (isDeleted() || value != null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
