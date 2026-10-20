package com.ffproject.flagconsumer.flag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ffproject.flagconsumer.config.ConsumerProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlagEventListenerTest {

    private final FlagStore store = new FlagStore(new ConsumerProperties(
            "flag-updates", null, null, null, null, 300_000));
    private final FlagEventListener listener = new FlagEventListener(store, new ObjectMapper());

    @Test
    void appliesValidJsonEvents() {
        listener.handle("""
                {"tenantId":"acme","name":"checkout-v2","value":true,"version":4,\
                "ts":"2026-10-18T12:34:56.789Z","event":"updated"}""");

        assertThat(store.get("acme", "checkout-v2")).isPresent();
        assertThat(store.get("acme", "checkout-v2").orElseThrow().version()).isEqualTo(4);
        assertThat(store.metrics().applied()).isEqualTo(1);
    }

    @Test
    void ignoresUnknownFieldsAndHandlesDeletions() {
        listener.handle("""
                {"tenantId":"acme","name":"checkout-v2","value":null,"version":5,\
                "ts":"2026-10-18T12:35:00Z","event":"deleted","extra":"ignored"}""");

        assertThat(store.get("acme", "checkout-v2")).isEmpty();
        assertThat(store.metrics().applied()).isEqualTo(1);
    }

    @Test
    void ignoresMalformedAndInvalidPayloads() {
        listener.handle("not json");
        listener.handle("{}");
        listener.handle("""
                {"tenantId":"acme","name":"x","value":true,"version":1,\
                "ts":"2026-10-18T12:00:00Z","event":"renamed"}""");

        assertThat(store.metrics().ignored()).isEqualTo(3);
        assertThat(store.metrics().applied()).isZero();
        assertThat(store.flagCount()).isZero();
    }
}
