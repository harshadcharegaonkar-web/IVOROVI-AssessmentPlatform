package com.ivorovi.platform.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Small JSON cache over Valkey used by kernel caches. Cache failures never break requests: a read error is a
 * miss (the caller falls back to the database) and is logged. Values are JSON, never Java serialization.
 */
@Component
public class ValkeyJsonCache {

    private static final Logger log = LoggerFactory.getLogger(ValkeyJsonCache.class);
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final MeterRegistry meters;

    ValkeyJsonCache(StringRedisTemplate redis, JsonMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.meters = meters;
    }

    public <T> Optional<T> get(String cacheName, String key, Class<T> type) {
        try {
            String raw = redis.opsForValue().get(key);
            meters.counter("ivorovi.cache.requests", "cache", cacheName, "result", raw == null ? "miss" : "hit").increment();
            return raw == null ? Optional.empty() : Optional.of(json.readValue(raw, type));
        } catch (RuntimeException e) {
            meters.counter("ivorovi.cache.requests", "cache", cacheName, "result", "error").increment();
            log.warn("Cache read failed for {}: {}", cacheName, e.toString());
            return Optional.empty();
        }
    }

    public void put(String cacheName, String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            log.warn("Cache write failed for {}: {}", cacheName, e.toString());
        }
    }

    public long counter(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            return raw == null ? 0 : Long.parseLong(raw);
        } catch (RuntimeException e) {
            log.warn("Cache counter read failed: {}", e.toString());
            return -1; // forces a key that has never been written: effectively a miss
        }
    }

    /** Runs the eviction after the current transaction commits (immediately when there is none). */
    public void afterCommit(Runnable eviction) {
        Runnable safe = () -> {
            try {
                eviction.run();
            } catch (RuntimeException e) {
                log.error("Cache eviction failed; entries expire by TTL", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    public void delete(String key) {
        redis.delete(key);
    }

    public void increment(String key) {
        redis.opsForValue().increment(key);
    }
}
