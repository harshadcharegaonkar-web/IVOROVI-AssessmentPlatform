package com.ivorovi.platform.security;

import com.ivorovi.platform.cache.ValkeyJsonCache;
import com.ivorovi.platform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Caches resolved principals (account + grants) per tenant for at most 60 s.
 *
 * <p>Keys embed the tenant id and a per-tenant generation number: {@link #evictTenant} invalidates every
 * principal of one tenant with a single increment, {@link #evictSubject} removes one user. Evictions run after
 * commit and apply to every API replica (shared Valkey). Negative results are never cached, so a newly
 * provisioned account works immediately.
 */
@Component
public class PrincipalCache {

    static final Duration TTL = Duration.ofSeconds(60);
    private static final String CACHE = "principal";
    private final ValkeyJsonCache cache;

    PrincipalCache(ValkeyJsonCache cache) {
        this.cache = cache;
    }

    Optional<IvoroviPrincipal> get(UUID tenantId, String subject) {
        requireBound(tenantId);
        return cache.get(CACHE, key(tenantId, subject), IvoroviPrincipal.class)
                .filter(p -> p.tenantId().equals(tenantId) && p.subject().equals(subject));
    }

    void put(IvoroviPrincipal principal) {
        requireBound(principal.tenantId());
        cache.put(CACHE, key(principal.tenantId(), principal.subject()), principal, TTL);
    }

    /** Call when one user's account status or role assignments change. */
    public void evictSubject(UUID tenantId, String subject) {
        cache.afterCommit(() -> cache.delete(key(tenantId, subject)));
    }

    /** Call when a role definition changes (affects every holder in the tenant). */
    public void evictTenant(UUID tenantId) {
        cache.afterCommit(() -> cache.increment(generationKey(tenantId)));
    }

    private String key(UUID tenantId, String subject) {
        return "ivorovi:principal:t:" + tenantId + ":g" + cache.counter(generationKey(tenantId)) + ":" + sha256(subject);
    }

    private static String generationKey(UUID tenantId) {
        return "ivorovi:principal-gen:t:" + tenantId;
    }

    private static void requireBound(UUID tenantId) {
        if (!tenantId.equals(TenantContext.requireTenantId())) {
            throw new IllegalStateException("Principal cache access for a tenant other than the bound tenant");
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
