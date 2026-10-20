package com.ffproject.flagconsumer.flag;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlagStoreTest {

    private static final String TENANT = "acme";
    private static final String TS = "2026-10-18T12:00:00Z";

    private final FlagStore store = newStore(null);

    private static FlagStore newStore(String tenantFilter) {
        return new FlagStore(new ConsumerProperties(
                "flag-updates", tenantFilter, null, null, null, 300_000));
    }

    private static FlagEvent event(String name, Boolean value, long version, String type) {
        return new FlagEvent(TENANT, name, value, version, TS, type);
    }

    @Test
    void appliesCreatedAndUpdatedEvents() {
        assertThat(store.apply(event("checkout-v2", true, 1, FlagEvent.CREATED)))
                .isEqualTo(FlagStore.ApplyResult.APPLIED);
        assertThat(store.get(TENANT, "checkout-v2"))
                .contains(new FlagView("checkout-v2", true, 1, TS));

        assertThat(store.apply(event("checkout-v2", false, 2, FlagEvent.UPDATED)))
                .isEqualTo(FlagStore.ApplyResult.APPLIED);
        assertThat(store.get(TENANT, "checkout-v2").orElseThrow().value()).isFalse();
        assertThat(store.get(TENANT, "checkout-v2").orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void ignoresEqualOrOlderVersions() {
        store.apply(event("checkout-v2", true, 3, FlagEvent.UPDATED));

        assertThat(store.apply(event("checkout-v2", false, 3, FlagEvent.UPDATED)))
                .isEqualTo(FlagStore.ApplyResult.STALE);
        assertThat(store.apply(event("checkout-v2", false, 2, FlagEvent.UPDATED)))
                .isEqualTo(FlagStore.ApplyResult.STALE);
        assertThat(store.get(TENANT, "checkout-v2").orElseThrow().value()).isTrue();
    }

    @Test
    void deletionHidesTheFlagAndBlocksResurrection() {
        store.apply(event("removed", true, 4, FlagEvent.CREATED));
        store.apply(event("removed", null, 5, FlagEvent.DELETED));

        assertThat(store.get(TENANT, "removed")).isEmpty();
        assertThat(store.list(TENANT)).isEmpty();

        // A delayed delivery of the old `created` must not bring it back.
        assertThat(store.apply(event("removed", true, 4, FlagEvent.CREATED)))
                .isEqualTo(FlagStore.ApplyResult.STALE);
        assertThat(store.get(TENANT, "removed")).isEmpty();
    }

    @Test
    void rejectsInvalidEvents() {
        assertThat(store.apply(null)).isEqualTo(FlagStore.ApplyResult.IGNORED);
        assertThat(store.apply(event("x", true, 1, "renamed")))
                .isEqualTo(FlagStore.ApplyResult.IGNORED);
        assertThat(store.apply(new FlagEvent(TENANT, "x", null, 1L, TS, FlagEvent.CREATED)))
                .isEqualTo(FlagStore.ApplyResult.IGNORED);
        assertThat(store.apply(new FlagEvent(TENANT, "x", true, -1L, TS, FlagEvent.CREATED)))
                .isEqualTo(FlagStore.ApplyResult.IGNORED);

        assertThat(store.flagCount()).isZero();
        assertThat(store.metrics().ignored()).isEqualTo(4);
    }

    @Test
    void filtersForeignTenantsWhenConfigured() {
        FlagStore filtered = newStore(TENANT);

        assertThat(filtered.apply(new FlagEvent("other", "checkout-v2", true, 1L, TS, FlagEvent.CREATED)))
                .isEqualTo(FlagStore.ApplyResult.IGNORED);
        assertThat(filtered.apply(event("checkout-v2", true, 1, FlagEvent.CREATED)))
                .isEqualTo(FlagStore.ApplyResult.APPLIED);

        assertThat(filtered.tenantCount()).isEqualTo(1);
        assertThat(filtered.flagCount()).isEqualTo(1);
        assertThat(filtered.metrics().ignored()).isEqualTo(1);
    }

    @Test
    void listsLiveFlagsSortedByName() {
        store.apply(event("search-v2", false, 1, FlagEvent.CREATED));
        store.apply(event("checkout-v2", true, 1, FlagEvent.CREATED));
        store.apply(event("removed", null, 1, FlagEvent.DELETED));

        assertThat(store.list(TENANT))
                .extracting(FlagView::name)
                .containsExactly("checkout-v2", "search-v2");
        assertThat(store.flagCount()).isEqualTo(2);
    }

    @Test
    void hydrateVersionChecksEachEntryAndSweepsMissingLiveFlags() {
        store.apply(event("existing", false, 5, FlagEvent.UPDATED));
        store.apply(event("gone", true, 1, FlagEvent.CREATED));

        store.hydrate(TENANT, List.of(
                new FlagSnapshot("existing", true, 4L, Instant.parse("2026-10-18T12:59:00Z")),
                new FlagSnapshot("fresh", true, 2L, Instant.parse("2026-10-18T12:59:00Z"))),
                Instant.parse("2026-10-18T13:00:00Z"));

        // Older snapshot entry: the cached version wins.
        assertThat(store.get(TENANT, "existing").orElseThrow().value()).isFalse();
        assertThat(store.get(TENANT, "fresh")).isPresent();
        assertThat(store.get(TENANT, "gone")).isEmpty();
        assertThat(store.list(TENANT)).hasSize(2);
    }

    @Test
    void hydrateKeepsFlagsThatLandedWhileTheSnapshotWasInFlight() {
        store.apply(new FlagEvent(TENANT, "mid-flight", true, 1L,
                "2026-10-18T13:00:01Z", FlagEvent.CREATED));

        store.hydrate(TENANT, List.of(), Instant.parse("2026-10-18T13:00:00Z"));

        assertThat(store.get(TENANT, "mid-flight")).isPresent();
    }

    @Test
    void hydrateToleratesMissingInput() {
        store.apply(event("checkout-v2", true, 1, FlagEvent.CREATED));

        assertThat(store.hydrate(TENANT, null, Instant.now())).isEqualTo(1);
        assertThat(store.hydrate(null, List.of(), Instant.now())).isZero();
        assertThat(store.flagCount()).isEqualTo(1);
    }
}
