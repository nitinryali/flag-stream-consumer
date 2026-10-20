package com.ffproject.flagconsumer.flag;

import com.ffproject.flagconsumer.config.ConsumerProperties;
import com.ffproject.flagconsumer.health.SubscriptionStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Owns the Redis subscription and keeps Redis out of the startup path.
 *
 * <p>{@link RedisMessageListenerContainer} implements plain {@code Lifecycle},
 * so if it were a bean Spring would start it during context refresh — and
 * {@code start()} connects synchronously and aborts the context when Redis is
 * unreachable. A failed start also leaves the container marked as running, so
 * it has to be stopped before the next attempt.</p>
 *
 * <p>Building it here instead lets the HTTP API and snapshot sync come up even
 * while Redis is down; the container then attaches as soon as Redis answers and
 * handles later reconnects itself.</p>
 */
@Component
public class FlagSubscription implements SubscriptionStatus {

    private static final Logger log = LoggerFactory.getLogger(FlagSubscription.class);

    private final RedisMessageListenerContainer container;

    public FlagSubscription(
            RedisConnectionFactory connectionFactory,
            FlagEventListener listener,
            ConsumerProperties properties) {

        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connectionFactory);

        // Retry quickly after Redis comes back instead of waiting the default 5s.
        this.container.setRecoveryInterval(2_000L);

        // One shared channel; events are filtered by tenant inside FlagStore.
        this.container.addMessageListener(listener, new ChannelTopic(properties.channel()));

        // Spring calls this only for beans; without it start() has no subscriber.
        this.container.afterPropertiesSet();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        attach();
    }

    @Scheduled(fixedDelay = 5_000)
    public void retry() {
        attach();
    }

    private synchronized void attach() {
        if (container.isRunning()) {
            return;
        }
        try {
            container.start();
            log.info("Subscribed to the flag event stream");
        } catch (RuntimeException e) {
            log.warn("Redis unavailable ({}); retrying in 5s", e.getMessage());
            try {
                container.stop();
            } catch (RuntimeException ignored) {
                // the failed start never established a subscription
            }
        }
    }

    @Override
    public boolean isListening() {
        return container.isListening();
    }

    @PreDestroy
    public void destroy() {
        try {
            container.destroy();
        } catch (Exception e) {
            log.warn("Failed to stop the Redis subscription cleanly: {}", e.getMessage());
        }
    }
}
