package com.ivorovi.platform.tenancy;

import com.ivorovi.platform.cache.ValkeyJsonCache;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Exam portal key → organization (Q-07: one shared exam domain, tenant resolved from trusted server-side configuration,
 * never from a header or token claim chosen by the browser). Uses the security-definer lookup {@code exam_portal_tenant}
 * because it runs before a tenant context exists; cached for 30 s, evicted when a portal changes.
 */
@Component
public class PortalDirectory {

    public static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{2,39}$");
    private static final Duration TTL = Duration.ofSeconds(30);
    public record Entry(UUID tenantId) {}

    private final JdbcClient jdbc;
    private final ValkeyJsonCache cache;

    PortalDirectory(JdbcClient jdbc, ValkeyJsonCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    public Optional<UUID> tenantOf(String portalKey) {
        if (portalKey == null || !KEY.matcher(portalKey).matches()) return Optional.empty();
        String key = "ivorovi:portal:" + portalKey;
        Optional<Entry> cached = cache.get("portal", key, Entry.class);
        if (cached.isPresent()) return Optional.ofNullable(cached.get().tenantId());
        UUID tenant = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT exam_portal_tenant(?)").param(portalKey).query(UUID.class).optional().orElse(null));
        cache.put("portal", key, new Entry(tenant), TTL);
        return Optional.ofNullable(tenant);
    }

    public void evict(String portalKey) {
        cache.afterCommit(() -> cache.delete("ivorovi:portal:" + portalKey));
    }
}
