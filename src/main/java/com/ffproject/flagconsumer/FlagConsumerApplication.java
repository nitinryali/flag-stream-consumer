package com.ffproject.flagconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Downstream consumer of the Flag Service.
 *
 * <p>Subscribes to the {@code flag-updates} Redis Pub/Sub channel, keeps the
 * flags it receives in memory, and serves them over a small read-only HTTP
 * API. It never writes flags back — the Flag Service stays the only source of
 * truth.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FlagConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlagConsumerApplication.class, args);
    }
}
