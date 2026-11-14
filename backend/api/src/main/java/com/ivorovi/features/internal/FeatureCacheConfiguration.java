package com.ivorovi.features.internal;

import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@Configuration(proxyBeanMethods = false)
class FeatureCacheConfiguration {

    /**
     * KI-80: immediate (synchronous) writes. Spring Data Redis 4 otherwise sends put/evict asynchronously on a separate
     * connection, so a put can land after the eviction that follows it and a changed feature flag keeps its old value
     * for the TTL. Transaction awareness stays: puts and evictions still apply only after commit.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer featureOverrideCache(RedisConnectionFactory connections) {
        return builder -> builder.cacheWriter(RedisCacheWriter.create(connections, w -> w.immediateWrites())).transactionAware().withCacheConfiguration(OverrideCache.CACHE,
                RedisCacheConfiguration.defaultCacheConfig()
                        .prefixCacheNameWith("ivorovi:")
                        .entryTtl(Duration.ofMinutes(10))
                        .disableCachingNullValues()
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(OverrideSnapshot.class))));
    }
}
