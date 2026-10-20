package com.ffproject.flagconsumer.health;

/**
 * Whether the Redis subscription is currently attached.
 *
 * <p>Extracted from {@code RedisMessageListenerContainer} so the web layer does
 * not depend on Redis at all — which also keeps the health endpoint testable
 * with a lambda instead of a mocked container.</p>
 */
@FunctionalInterface
public interface SubscriptionStatus {

    boolean isListening();
}
