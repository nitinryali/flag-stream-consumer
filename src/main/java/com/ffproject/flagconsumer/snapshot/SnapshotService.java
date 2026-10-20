package com.ffproject.flagconsumer.snapshot;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import com.ffproject.flagconsumer.flag.FlagSnapshot;
import com.ffproject.flagconsumer.flag.FlagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Closes the gap that Redis Pub/Sub leaves behind.
 *
 * <p>Pub/Sub has no replay: a consumer that was restarted, or offline for a
 * moment, can never recover the messages it missed from the stream. So this
 * service re-reads the authoritative {@code GET /flags} snapshot on startup and
 * then every {@code consumer.snapshot-refresh-ms}.</p>
 *
 * <p>Each run logs in again rather than caching a token. That is one extra
 * request every few minutes and removes any expiry bookkeeping; keep the
 * interval below the Flag Service's JWT lifetime either way.</p>
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final ConsumerProperties properties;
    private final FlagServiceClient client;
    private final FlagStore store;
    private volatile Status status;

    public SnapshotService(ConsumerProperties properties, FlagServiceClient client, FlagStore store) {
        this.properties = properties;
        this.client = client;
        this.store = store;
        this.status = properties.snapshotEnabled() ? Status.pending() : Status.disabled();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refresh();
    }

    @Scheduled(
            fixedDelayString = "${consumer.snapshot-refresh-ms:300000}",
            initialDelayString = "${consumer.snapshot-refresh-ms:300000}")
    public synchronized void refresh() {
        if (!properties.snapshotEnabled()) {
            return;
        }

        Instant requestedAt = Instant.now();
        try {
            String token = client.login();
            List<FlagSnapshot> flags = client.fetchFlags(token);
            store.hydrate(properties.tenantId(), flags, requestedAt);
            status = new Status(true, true, flags.size(), Instant.now(), null);
            log.info("Snapshot loaded: {} flag(s) for tenant {}", flags.size(), properties.tenantId());
        } catch (RuntimeException e) {
            status = new Status(true, false, status.flags(), Instant.now(), e.getMessage());
            log.warn("Snapshot refresh failed: {}", e.getMessage());
        }
    }

    public Status status() {
        return status;
    }

    /**
     * @param enabled whether snapshot sync is configured at all
     * @param ok      whether the last refresh succeeded
     * @param flags   flag count reported by the last successful refresh
     * @param at      when the last refresh finished
     * @param error   failure message of the last attempt, null on success
     */
    public record Status(boolean enabled, boolean ok, int flags, Instant at, String error) {

        public static Status disabled() {
            return new Status(false, false, 0, null, null);
        }

        public static Status pending() {
            return new Status(true, false, 0, null, null);
        }
    }
}
