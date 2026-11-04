package com.ivorovi.integrations.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the integration framework ({@code ivorovi.integrations.*}).
 *
 * @param allowPrivateNetworks  allow outbound calls to loopback/private addresses (local development only; never in production)
 * @param allowInsecureHttp     allow plain HTTP targets (local development only)
 * @param allowedHosts          optional allow-list of outbound host names ({@code *.example.edu} patterns); empty = any public host
 * @param requestTimeout        connect and response timeout of every outbound call
 * @param runAttempts           attempts inside one connection test or sync (with short backoff)
 * @param failingThreshold      consecutive failures after which managers are notified
 * @param healthCheckInterval   interval of scheduled connection checks of enabled integrations (zero = off)
 * @param webhooks              webhook delivery settings
 */
@ConfigurationProperties("ivorovi.integrations")
public record IntegrationsProperties(boolean allowPrivateNetworks, boolean allowInsecureHttp, List<String> allowedHosts, Duration requestTimeout,
                                     int runAttempts, int failingThreshold, Duration healthCheckInterval, Webhooks webhooks) {

    /**
     * @param dispatcherEnabled  deliver pending webhooks on this instance
     * @param consumerEnabled    consume domain events for fan-out on this instance
     * @param maxAttempts        delivery attempts before a delivery is FAILED
     * @param disableAfter       consecutive failed deliveries after which the endpoint is AUTO_DISABLED
     * @param backoffBase        first retry delay (doubles per attempt, capped at one hour)
     * @param concurrency        parallel deliveries on this instance
     */
    public record Webhooks(boolean dispatcherEnabled, boolean consumerEnabled, int maxAttempts, int disableAfter, Duration backoffBase, int concurrency) {
        public Webhooks {
            if (maxAttempts <= 0) maxAttempts = 8;
            if (disableAfter <= 0) disableAfter = 10;
            if (backoffBase == null) backoffBase = Duration.ofSeconds(30);
            if (concurrency <= 0) concurrency = 4;
        }
    }

    public IntegrationsProperties {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream().map(String::trim).filter(h -> !h.isEmpty()).toList();
        if (requestTimeout == null) requestTimeout = Duration.ofSeconds(5);
        if (runAttempts <= 0) runAttempts = 3;
        if (failingThreshold <= 0) failingThreshold = 3;
        if (healthCheckInterval == null) healthCheckInterval = Duration.ofMinutes(15);
        if (webhooks == null) webhooks = new Webhooks(true, true, 8, 10, Duration.ofSeconds(30), 4);
    }
}
