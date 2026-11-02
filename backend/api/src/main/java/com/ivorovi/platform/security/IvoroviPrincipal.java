package com.ivorovi.platform.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The authenticated user, verified against the Ivorovi database for the token's tenant.
 * {@code roles} lists every currently valid role assignment, including roles that grant no permissions yet
 * (e.g. STUDENT in Phase 1), so roles never disappear just because their permission set is empty.
 */
public record IvoroviPrincipal(
        UUID userId, UUID tenantId, String subject, String email, String displayName, List<Grant> grants,
        List<String> roles) {

    public IvoroviPrincipal {
        grants = List.copyOf(grants);
        // tolerate cache entries written before roles were stored: derive from grants
        roles = roles == null ? grants.stream().map(Grant::roleCode).distinct().sorted().toList() : List.copyOf(roles);
    }

    public Set<String> roleCodes() {
        return roles.stream().collect(Collectors.toUnmodifiableSet());
    }
}
