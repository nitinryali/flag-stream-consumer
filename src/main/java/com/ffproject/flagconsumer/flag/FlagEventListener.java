package com.ffproject.flagconsumer.flag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Turns raw {@code flag-updates} messages into cache updates.
 *
 * <p>The listener is intentionally thin: parse, validate, delegate. Every
 * decision about versions, tenants and tombstones lives in {@link FlagStore},
 * which keeps it unit-testable without Redis.</p>
 */
@Component
public class FlagEventListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(FlagEventListener.class);
    private static final int LOG_PAYLOAD_LIMIT = 200;

    private final FlagStore store;
    private final ObjectMapper objectMapper;

    public FlagEventListener(FlagStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, @Nullable byte[] pattern) {
        byte[] body = message.getBody();
        handle(body == null ? "" : new String(body, StandardCharsets.UTF_8));
    }

    /**
     * Parses and applies one payload.
     *
     * <p>Package-private so tests can exercise it without constructing a
     * Spring {@link Message}.</p>
     */
    void handle(String payload) {
        FlagEvent event;
        try {
            event = objectMapper.readValue(payload, FlagEvent.class);
        } catch (JsonProcessingException e) {
            store.ignore();
            log.warn("Ignoring malformed flag event: {}", abbreviate(payload));
            return;
        }

        if (event == null || !event.isValid()) {
            store.ignore();
            log.warn("Ignoring invalid flag event: {}", abbreviate(payload));
            return;
        }

        switch (store.apply(event)) {
            case APPLIED -> log.info("Applied {} event: {}/{} v{}",
                    event.event(), event.tenantId(), event.name(), event.version());
            case STALE -> log.info("Ignored stale {} event: {}/{} v{}",
                    event.event(), event.tenantId(), event.name(), event.version());
            case IGNORED -> log.info("Ignored event for foreign tenant: {}/{}",
                    event.tenantId(), event.name());
        }
    }

    private static String abbreviate(String payload) {
        return payload.length() <= LOG_PAYLOAD_LIMIT
                ? payload
                : payload.substring(0, LOG_PAYLOAD_LIMIT) + "...";
    }
}
