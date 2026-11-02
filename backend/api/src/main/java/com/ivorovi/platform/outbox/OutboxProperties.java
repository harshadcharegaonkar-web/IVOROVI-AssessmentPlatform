package com.ivorovi.platform.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ivorovi.outbox")
public record OutboxProperties(String exchange, int batchSize, Duration confirmTimeout, Duration maxBackoff,
                               boolean publisherEnabled) {

    public OutboxProperties {
        if (exchange == null) exchange = "ivorovi.events";
        if (batchSize <= 0) batchSize = 100;
        if (confirmTimeout == null) confirmTimeout = Duration.ofSeconds(5);
        if (maxBackoff == null) maxBackoff = Duration.ofMinutes(10);
    }
}
