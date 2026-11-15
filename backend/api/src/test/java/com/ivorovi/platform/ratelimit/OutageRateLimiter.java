package com.ivorovi.platform.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A {@link RateLimiter} whose Valkey can be switched off: while {@link #down} is set, every call answers exactly like the real
 * limiter does when Valkey is unreachable (allowed, counters 0, flags false: fail open). For tests of the layers that must
 * hold without Valkey (KI-89).
 */
public class OutageRateLimiter extends RateLimiter {

    public final AtomicBoolean down = new AtomicBoolean();

    public OutageRateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
        super(redis, meters);
    }

    @Override
    public Decision acquire(String key, int perMinute, boolean consume) {
        return down.get() ? new Decision(true, perMinute, perMinute, 0, 0) : super.acquire(key, perMinute, consume);
    }

    @Override
    public Decision acquire(String key, int capacity, Duration window, boolean consume) {
        return down.get() ? new Decision(true, capacity, capacity, 0, 0) : super.acquire(key, capacity, window, consume);
    }

    @Override
    public long increment(String key, Duration ttl) {
        return down.get() ? 0 : super.increment(key, ttl);
    }

    @Override
    public boolean once(String key, Duration ttl) {
        return !down.get() && super.once(key, ttl);
    }
}
