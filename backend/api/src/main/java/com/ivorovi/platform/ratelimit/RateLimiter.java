package com.ivorovi.platform.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Token buckets and windowed counters in Valkey (shared by all replicas). Fails open, with a metric, if Valkey is
 * unavailable: a limiter outage never refuses a request.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds, long resetSeconds) {}

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String PREFIX = "ivorovi:rl:";
    private static final Duration MINUTE = Duration.ofMinutes(1);
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    RateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/token-bucket.lua"));
        this.script.setResultType(List.class);
    }

    /** Consumes one token (or, with {@code consume=false}, only checks that one is available). */
    public Decision acquire(String key, int perMinute, boolean consume) {
        return acquire(key, perMinute, MINUTE, consume);
    }

    /**
     * A bucket of {@code capacity} tokens refilled evenly over {@code window} (e.g. 20 per day). Consumes one token, or
     * with {@code consume=false} only checks that one is available.
     */
    public Decision acquire(String key, int capacity, Duration window, boolean consume) {
        double ratePerMs = capacity / (double) window.toMillis();
        try {
            List<?> r = redis.execute(script, List.of(PREFIX + key),
                    String.valueOf(capacity), String.valueOf(ratePerMs), consume ? "1" : "0");
            boolean allowed = ((Number) r.get(0)).longValue() == 1;
            long remaining = ((Number) r.get(1)).longValue();
            long retryMs = ((Number) r.get(2)).longValue();
            long reset = (long) Math.ceil((capacity - remaining) / (capacity / (double) window.toSeconds()));
            if (!allowed) meters.counter("ivorovi.ratelimit.rejected", "bucket", bucket(key)).increment();
            return new Decision(allowed, capacity, remaining, Math.max(1, (retryMs + 999) / 1000), reset);
        } catch (RuntimeException e) {
            failOpen(e);
            return new Decision(true, capacity, capacity, 0, 0);
        }
    }

    /**
     * Increments a counter that lives for {@code ttl} from its first increment and returns the new value (fixed window:
     * put the window start into the key). Returns 0 when Valkey is unavailable.
     */
    public long increment(String key, Duration ttl) {
        try {
            Long v = redis.opsForValue().increment(PREFIX + key);
            if (v != null && v == 1) redis.expire(PREFIX + key, ttl);
            return v == null ? 0 : v;
        } catch (RuntimeException e) {
            failOpen(e);
            return 0;
        }
    }

    /** True exactly once per key and {@code ttl} across all replicas (e.g. one alert per address and minute). */
    public boolean once(String key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + key, "1", ttl));
        } catch (RuntimeException e) {
            failOpen(e);
            return false;
        }
    }

    private void failOpen(RuntimeException e) {
        meters.counter("ivorovi.ratelimit.failopen").increment();
        log.warn("Rate limiter unavailable, failing open: {}", e.toString());
    }

    private static String bucket(String key) {
        int i = key.indexOf(':');
        return i < 0 ? key : key.substring(0, i);
    }
}
