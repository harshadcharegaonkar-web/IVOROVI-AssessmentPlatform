package com.ivorovi.platform.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The authenticated user, verified against the Ivorovi database for the token's tenant.
 * {@code roles} lists every currently valid role assignment, including roles that grant no permissions yet
 * (e.g. STUDENT in Phase 1), so roles never disappear just because their permission set is empty.
 * {@code refreshBy} is the next moment a role assignment starts or ends; cached copies expire no later than that,
 * so a dated assignment takes effect (or stops) on time rather than up to one cache lifetime late.
 */
public record IvoroviPrincipal(
        UUID userId, UUID tenantId, String subject, String email, String displayName, List<Grant> grants,
        List<String> roles, java.time.Instant refreshBy) {

    public IvoroviPrincipal(UUID userId, UUID tenantId, String subject, String email, String displayName,
                            List<Grant> grants, List<String> roles) {
        this(userId, tenantId, subject, email, displayName, grants, roles, null);
    }

    public IvoroviPrincipal {
        grants = List.copyOf(grants);
        // tolerate cache entries written before roles were stored: derive from grants
        roles = roles == null ? grants.stream().map(Grant::roleCode).distinct().sorted().toList() : List.copyOf(roles);
    }

    public Set<String> roleCodes() {
        return roles.stream().collect(Collectors.toUnmodifiableSet());
    }
}
