package com.ivorovi.integrations.internal;

import com.ivorovi.platform.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API-client usage counters. Counted in memory per instance and written in batches (default every 10 s and on
 * shutdown), so concurrent requests of one client do not queue on the same daily counter row. Usage is visibility data
 * only; authorization never depends on it.
 */
@Component
public class ApiClientUsage {

    private static final Logger log = LoggerFactory.getLogger(ApiClientUsage.class);

    private record Key(UUID tenant, UUID client) {}

    private static final class Counts {
        final LongAdder requests = new LongAdder();
        final LongAdder denied = new LongAdder();
    }

    private final Map<Key, Counts> pending = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    ApiClientUsage(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    void count(UUID tenant, UUID client, boolean denied) {
        Counts c = pending.computeIfAbsent(new Key(tenant, client), k -> new Counts());
        c.requests.increment();
        if (denied) c.denied.increment();
    }

    /** Writes and clears the pending counts (also called by tests before reading usage). */
    @Scheduled(fixedDelayString = "${ivorovi.integrations.usage-flush-interval:PT10S}")
    public void flush() {
        for (Key k : pending.keySet()) {
            Counts c = pending.remove(k);
            if (c == null) continue;
            long requests = c.requests.sum(), denied = c.denied.sum();
            if (requests == 0) continue;
            try {
                TenantContext.runInTenant(k.tenant(), () -> tx.executeWithoutResult(s -> jdbc.sql("""
                        INSERT INTO api_client_usage (tenant_id, client_id, day, requests, denied) VALUES (?, ?, current_date, ?, ?)
                        ON CONFLICT (tenant_id, client_id, day) DO UPDATE SET requests = api_client_usage.requests + EXCLUDED.requests,
                            denied = api_client_usage.denied + EXCLUDED.denied""").params(k.tenant(), k.client(), requests, denied).update()));
            } catch (RuntimeException e) {
                log.warn("API client usage not recorded: {}", e.getClass().getSimpleName());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        flush();
    }
}
