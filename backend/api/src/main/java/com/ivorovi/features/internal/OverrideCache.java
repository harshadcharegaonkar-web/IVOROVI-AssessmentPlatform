package com.ivorovi.features.internal;

import com.ivorovi.platform.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Per-tenant cache of overrides. The cache manager is transaction-aware: evictions apply after commit. */
@Component
public class OverrideCache {

    public static final String CACHE = "feature-overrides";
    private final FeatureFlagOverrideRepository repository;

    OverrideCache(FeatureFlagOverrideRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CACHE, key = "#tenantId")
    @Transactional(readOnly = true)
    public OverrideSnapshot load(UUID tenantId) {
        if (!tenantId.equals(TenantContext.requireTenantId())) {
            throw new IllegalStateException("Cache key tenant differs from tenant context");
        }
        return new OverrideSnapshot(repository.findAll().stream()
                .map(o -> new OverrideSnapshot.Entry(o.getFlagKey(), o.getOrgUnitId(), o.isEnabled())).toList());
    }

    @CacheEvict(cacheNames = CACHE, key = "#tenantId")
    public void evict(UUID tenantId) {
        // eviction performed by the annotation
    }
}
