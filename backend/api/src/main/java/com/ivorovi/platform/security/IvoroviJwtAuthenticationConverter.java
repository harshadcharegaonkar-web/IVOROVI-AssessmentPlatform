package com.ivorovi.platform.security;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.tenancy.TenantDirectory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a verified JWT into an {@link IvoroviAuthentication}:
 * <ol>
 *   <li>the tenant comes only from the signed {@code tid} claim (never from headers or the request body);</li>
 *   <li>the tenant must exist and be TRIAL/ACTIVE;</li>
 *   <li>the token subject must map to an ACTIVE user account inside that tenant (RLS-scoped lookup).</li>
 * </ol>
 */
@Component
public class IvoroviJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String TENANT_CLAIM = "tid";
    private static final Logger log = LoggerFactory.getLogger(IvoroviJwtAuthenticationConverter.class);

    private final TenantDirectory tenants;
    private final PrincipalResolver principals;
    private final PrincipalCache cache;

    IvoroviJwtAuthenticationConverter(TenantDirectory tenants, PrincipalResolver principals, PrincipalCache cache) {
        this.tenants = tenants;
        this.principals = principals;
        this.cache = cache;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID tenantId = parseTenant(jwt.getClaimAsString(TENANT_CLAIM));
        var tenant = tenants.find(tenantId).orElseThrow(() -> deny("unknown_tenant", jwt));
        if (!tenant.status().allowsAccess()) {
            throw deny("tenant_" + tenant.status().name().toLowerCase(), jwt);
        }
        TenantContext.bindRequestTenant(tenantId);
        MDC.put("tenantId", tenantId.toString());
        IvoroviPrincipal principal = cache.get(tenantId, jwt.getSubject())
                .or(() -> principals.resolve(tenantId, jwt.getSubject()).map(p -> { cache.put(p); return p; }))
                .orElseThrow(() -> { TenantContext.clear(); return deny("no_active_account", jwt); });
        MDC.put("userId", principal.userId().toString());
        return new IvoroviAuthentication(principal, jwt);
    }

    private static UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new TenantAccessDeniedException("missing_tenant_claim");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new TenantAccessDeniedException("invalid_tenant_claim");
        }
    }

    private static TenantAccessDeniedException deny(String reason, Jwt jwt) {
        // Security event without tenant attribution (no trusted tenant): structured log, never the raw token.
        log.warn("security.tenant_access_denied reason={} subject={} tid={}", reason, jwt.getSubject(),
                jwt.getClaimAsString(TENANT_CLAIM));
        return new TenantAccessDeniedException(reason);
    }
}
