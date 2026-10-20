package com.ffproject.flagconsumer.flag;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory flag cache fed by {@code flag-updates} events and by snapshots.
 *
 * <p>Layout: {@code tenantId -> name -> CachedFlag}. Two rules keep it coherent
 * despite fire-and-forget Pub/Sub:</p>
 *
 * <ul>
 *   <li>An event is applied only when its {@code version} is newer than the
 *       cached one, so out-of-order deliveries cannot roll a flag back.</li>
 *   <li>A deletion is kept as a tombstone ({@code value == null}) instead of
 *       being erased. The version high-water mark survives, so a delayed
 *       {@code created} cannot resurrect the flag. Reads filter tombstones.</li>
 * </ul>
 *
 * <p>Tombstones are deliberately never swept: they are the only thing that
 * keeps a deleted flag deleted. They cost one small object per deleted flag
 * name, which is a better trade than a resurrection bug.</p>
 */
@Component
public class FlagStore {

    /** Outcome of applying one event, used for logging and metrics. */
    public enum ApplyResult { APPLIED, STALE, IGNORED }

    /** Counters exposed on {@code /health}. */
    public record Metrics(long applied, long stale, long ignored) {
    }

    private record CachedFlag(Boolean value, long version, String ts) {
        boolean deleted() {
            return value == null;
        }
    }

    private final Map<String, Map<String, CachedFlag>> byTenant = new ConcurrentHashMap<>();
    private final LongAdder applied = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private final LongAdder ignored = new LongAdder();
    private final String tenantFilter;
    private volatile Instant lastEventAt;

    public FlagStore(ConsumerProperties properties) {
        this.tenantFilter = properties.tenantId();
    }

    /**
     * Applies one event. Validation happens here as well as in the listener so
     * the store is safe to call directly from tests and from the snapshot path.
     *
     * @return {@link ApplyResult#APPLIED} when the cache changed,
     *         {@link ApplyResult#STALE} for an old version, otherwise
     *         {@link ApplyResult#IGNORED}
     */
    public ApplyResult apply(FlagEvent event) {
        if (event == null || !event.isValid()) {
            return ignore();
        }
        if (tenantFilter != null && !tenantFilter.equals(event.tenantId())) {
            return ignore();
        }

        Map<String, CachedFlag> tenant =
                byTenant.computeIfAbsent(event.tenantId(), key -> new ConcurrentHashMap<>());
        AtomicReference<ApplyResult> outcome = new AtomicReference<>(ApplyResult.IGNORED);

        // compute() is atomic per flag name, so concurrent listeners cannot
        // apply two versions of the same flag out of order.
        tenant.compute(event.name(), (name, cached) -> {
            if (cached != null && cached.version() >= event.version()) {
                outcome.set(ApplyResult.STALE);
                return cached;
            }
            outcome.set(ApplyResult.APPLIED);
            return new CachedFlag(
                    event.isDeleted() ? null : event.value(),
                    event.version(),
                    event.ts());
        });

        return switch (outcome.get()) {
            case APPLIED -> {
                applied.increment();
                lastEventAt = Instant.now();
                yield ApplyResult.APPLIED;
            }
            case STALE -> {
                stale.increment();
                yield ApplyResult.STALE;
            }
            case IGNORED -> ignore();
        };
    }

    /** @return the live flag, or empty when unknown or tombstoned */
    public Optional<FlagView> get(String tenantId, String name) {
        Map<String, CachedFlag> tenant = byTenant.get(tenantId);
        CachedFlag flag = tenant == null ? null : tenant.get(name);
        if (flag == null || flag.deleted()) {
            return Optional.empty();
        }
        return Optional.of(new FlagView(name, flag.value(), flag.version(), flag.ts()));
    }

    /** @return the tenant's live flags, sorted by name */
    public List<FlagView> list(String tenantId) {
        Map<String, CachedFlag> tenant = byTenant.get(tenantId);
        if (tenant == null) {
            return List.of();
        }
        List<FlagView> flags = new ArrayList<>(tenant.size());
        tenant.forEach((name, flag) -> {
            if (!flag.deleted()) {
                flags.add(new FlagView(name, flag.value(), flag.version(), flag.ts()));
            }
        });
        flags.sort(Comparator.comparing(FlagView::name));
        return flags;
    }

    /**
     * Merges an authoritative {@code GET /flags} snapshot into the cache.
     *
     * <p>Entries are version-checked, so events that land while the snapshot is
     * in flight are not overwritten by older data. Live flags missing from the
     * snapshot are dropped — unless they arrived after {@code requestedAt},
     * i.e. while the request was still open. Tombstones are always kept.</p>
     *
     * @param requestedAt instant taken before the snapshot request was sent
     * @return number of cached entries for the tenant afterwards, tombstones
     *         included
     */
    public int hydrate(String tenantId, List<FlagSnapshot> snapshot, Instant requestedAt) {
        if (tenantId == null) {
            return 0;
        }
        Map<String, CachedFlag> tenant = byTenant.get(tenantId);
        if (snapshot == null) {
            return tenant == null ? 0 : tenant.size();
        }

        tenant = byTenant.computeIfAbsent(tenantId, key -> new ConcurrentHashMap<>());
        Set<String> seen = new HashSet<>();

        for (FlagSnapshot item : snapshot) {
            if (item == null || item.name() == null || item.name().isBlank() || item.version() == null) {
                continue;
            }
            seen.add(item.name());
            tenant.compute(item.name(), (name, cached) -> {
                if (cached != null && cached.version() > item.version()) {
                    return cached;
                }
                return new CachedFlag(
                        Boolean.TRUE.equals(item.value()),
                        item.version(),
                        item.updatedAt() == null ? null : item.updatedAt().toString());
            });
        }

        tenant.entrySet().removeIf(entry ->
                !seen.contains(entry.getKey())
                        && !entry.getValue().deleted()
                        && !isAfter(entry.getValue().ts(), requestedAt));

        return tenant.size();
    }

    public int tenantCount() {
        return byTenant.size();
    }

    public int flagCount() {
        int count = 0;
        for (Map<String, CachedFlag> tenant : byTenant.values()) {
            for (CachedFlag flag : tenant.values()) {
                if (!flag.deleted()) {
                    count++;
                }
            }
        }
        return count;
    }

    public Metrics metrics() {
        return new Metrics(applied.sum(), stale.sum(), ignored.sum());
    }

    /** When a live event was last applied; null until the first one. */
    public Instant lastEventAt() {
        return lastEventAt;
    }

    /** Counts a payload the consumer could not use, and returns the outcome. */
    public ApplyResult ignore() {
        ignored.increment();
        return ApplyResult.IGNORED;
    }

    private static boolean isAfter(String value, Instant reference) {
        if (value == null || reference == null) {
            return false;
        }
        Instant parsed = parseInstant(value);
        return parsed != null && parsed.isAfter(reference);
    }

    /** Tolerates the mixed ISO precision the two endpoints produce. */
    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException first) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException second) {
                return null;
            }
        }
    }
}
