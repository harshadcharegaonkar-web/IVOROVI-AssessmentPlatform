package com.ivorovi.platform.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox publishing. {@code publisherThreads} workers drain the backlog in parallel (rows claimed with
 * {@code FOR UPDATE SKIP LOCKED}); each batch is confirmed by the broker once (KI-64).
 */
@ConfigurationProperties("ivorovi.outbox")
public record OutboxProperties(String exchange, int batchSize, Duration confirmTimeout, Duration maxBackoff,
                               boolean publisherEnabled, int publisherThreads, Duration drainBudget) {

    public OutboxProperties {
        if (exchange == null) exchange = "ivorovi.events";
        if (batchSize <= 0) batchSize = 500;
        if (confirmTimeout == null) confirmTimeout = Duration.ofSeconds(10);
        if (maxBackoff == null) maxBackoff = Duration.ofMinutes(10);
        if (publisherThreads <= 0) publisherThreads = 4;
        if (drainBudget == null) drainBudget = Duration.ofSeconds(5);
    }
}
