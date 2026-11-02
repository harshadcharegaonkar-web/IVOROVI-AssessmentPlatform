package com.ivorovi.features.internal;

import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@Configuration(proxyBeanMethods = false)
class FeatureCacheConfiguration {

    @Bean
    RedisCacheManagerBuilderCustomizer featureOverrideCache() {
        return builder -> builder.transactionAware().withCacheConfiguration(OverrideCache.CACHE,
                RedisCacheConfiguration.defaultCacheConfig()
                        .prefixCacheNameWith("ivorovi:")
                        .entryTtl(Duration.ofMinutes(10))
                        .disableCachingNullValues()
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(OverrideSnapshot.class))));
    }
}
