package com.ivorovi.platform.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Token buckets in Valkey (shared by all replicas). Fails open, with a metric, if Valkey is unavailable. */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds, long resetSeconds) {}

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
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
        double ratePerMs = perMinute / 60_000.0;
        try {
            List<?> r = redis.execute(script, List.of("ivorovi:rl:" + key),
                    String.valueOf(perMinute), String.valueOf(ratePerMs), consume ? "1" : "0");
            boolean allowed = ((Number) r.get(0)).longValue() == 1;
            long remaining = ((Number) r.get(1)).longValue();
            long retryMs = ((Number) r.get(2)).longValue();
            long reset = (long) Math.ceil((perMinute - remaining) / (perMinute / 60.0));
            if (!allowed) meters.counter("ivorovi.ratelimit.rejected", "bucket", key.substring(0, key.indexOf(':'))).increment();
            return new Decision(allowed, perMinute, remaining, Math.max(1, (retryMs + 999) / 1000), reset);
        } catch (RuntimeException e) {
            meters.counter("ivorovi.ratelimit.failopen").increment();
            log.warn("Rate limiter unavailable, failing open: {}", e.toString());
            return new Decision(true, perMinute, perMinute, 0, 0);
        }
    }
}
