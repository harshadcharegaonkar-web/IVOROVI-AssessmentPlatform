package com.ivorovi.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.ivorovi.features.internal.OverrideSnapshot;
import com.ivorovi.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;

/**
 * KI-80 regression (QA, D-7): on the application's feature-override cache, a value written and then evicted must be gone —
 * immediately, and still gone a moment later. Spring Data Redis 4 writes and deletes cache entries asynchronously on a
 * separate (reactive) connection by default, so a put can land after the eviction that follows it and the stale snapshot
 * stays for the cache TTL (10 minutes). Measured on the unfixed configuration: 13–29 % of put → evict sequences left the
 * entry behind; with {@code RedisCacheWriter.create(factory, w -> w.immediateWrites())}: 0.
 *
 * <p>Disabled until the fix in {@code FeatureCacheConfiguration} (production code, primary session) is in: then remove
 * {@code @Disabled}. It fails deterministically on the current configuration, so it is not a flaky test.
 */
@Disabled("KI-80: fails until the feature-override cache uses immediate (synchronous) writes — enable together with the fix")
class FeatureCacheOrderingIT extends IntegrationTest {

    @Autowired CacheManager cacheManager;

    @Test
    void anEvictedEntryIsGoneAndStaysGone() throws Exception {
        Cache c = cacheManager.getCache("feature-overrides");
        Cache cache = c instanceof TransactionAwareCacheDecorator d ? d.getTargetCache() : c;
        OverrideSnapshot value = new OverrideSnapshot(List.of(new OverrideSnapshot.Entry("module.proctoring", null, true)));
        int immediatelyStale = 0;
        List<UUID> keys = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            UUID k = UUID.randomUUID();
            keys.add(k);
            cache.put(k, value);
            cache.evict(k);
            if (cache.get(k) != null) immediatelyStale++;
        }
        Thread.sleep(300);
        long stillThere = keys.stream().filter(k -> cache.get(k) != null).count();
        assertThat(immediatelyStale).as("reads right after an eviction that returned the evicted value").isZero();
        assertThat(stillThere).as("evicted entries present again 300 ms later (a put that landed after its eviction)").isZero();
        keys.forEach(cache::evictIfPresent);
    }
}
