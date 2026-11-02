package com.ivorovi.platform.tenancy;

import com.ivorovi.platform.cache.ValkeyJsonCache;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Resolves tenants (cached for 60 s). Runs in platform context because it executes before a tenant context exists.
 * Status changes must call {@link #evict} so suspension takes effect on the next request.
 */
@Component
public class TenantDirectory {

    public enum Status { TRIAL, ACTIVE, SUSPENDED, TERMINATED;
        public boolean allowsAccess() { return this == TRIAL || this == ACTIVE; }
    }

    public record TenantInfo(UUID id, String code, String name, Status status, String brandingJson) {}

    private static final Duration TTL = Duration.ofSeconds(60);
    private final JdbcClient jdbc;
    private final ValkeyJsonCache cache;

    TenantDirectory(JdbcClient jdbc, ValkeyJsonCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    public Optional<TenantInfo> find(UUID tenantId) {
        String key = "ivorovi:tenant:" + tenantId;
        Optional<TenantInfo> cached = cache.get("tenant", key, TenantInfo.class);
        if (cached.isPresent()) return cached;
        Optional<TenantInfo> loaded = load(tenantId);
        loaded.ifPresent(t -> cache.put("tenant", key, t, TTL));
        return loaded;
    }

    public void evict(UUID tenantId) {
        cache.afterCommit(() -> cache.delete("ivorovi:tenant:" + tenantId));
    }

    private Optional<TenantInfo> load(UUID tenantId) {
        return TenantContext.callAsPlatform(() -> jdbc
                .sql("SELECT id, code, name, status, branding::text AS branding FROM tenant WHERE id = ?")
                .param(tenantId)
                .query((rs, n) -> new TenantInfo(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), Status.valueOf(rs.getString("status")), rs.getString("branding")))
                .optional());
    }
}
