package com.ivorovi.tenancy.internal;

import com.ivorovi.platform.cache.ValkeyJsonCache;
import com.ivorovi.platform.security.OrgScopeResolver;
import com.ivorovi.platform.tenancy.TenantContext;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tenant-scoped cache of unit paths used by scope authorization (Authz). Keys embed the bound tenant id.
 * Moves and deletions evict every affected unit after commit; creations need no eviction (new keys).
 */
@Component
class OrgPathCache implements OrgScopeResolver {

    private static final Duration TTL = Duration.ofMinutes(10);
    private final JdbcClient jdbc;
    private final ValkeyJsonCache cache;

    OrgPathCache(JdbcClient jdbc, ValkeyJsonCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @Override
    public Optional<String> pathOf(UUID orgUnitId) {
        String key = key(TenantContext.requireTenantId(), orgUnitId);
        Optional<String> cached = cache.get("orgpath", key, String.class);
        if (cached.isPresent()) return cached;
        Optional<String> path = jdbc.sql("SELECT path::text FROM org_unit WHERE id = ?").param(orgUnitId)
                .query(String.class).optional();
        path.ifPresent(p -> cache.put("orgpath", key, p, TTL));
        return path;
    }

    void evictAfterCommit(Collection<UUID> ids) {
        UUID tenant = TenantContext.requireTenantId();
        cache.afterCommit(() -> ids.forEach(id -> cache.delete(key(tenant, id))));
    }

    private static String key(UUID tenant, UUID unit) {
        return "ivorovi:orgpath:t:" + tenant + ":" + unit;
    }
}
