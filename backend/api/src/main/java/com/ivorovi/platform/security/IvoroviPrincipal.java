package com.ivorovi.platform.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The authenticated user, verified against the Ivorovi database for the token's tenant. */
public record IvoroviPrincipal(
        UUID userId, UUID tenantId, String subject, String email, String displayName, List<Grant> grants) {

    public IvoroviPrincipal {
        grants = List.copyOf(grants);
    }

    public Set<String> roleCodes() {
        return grants.stream().map(Grant::roleCode).collect(Collectors.toUnmodifiableSet());
    }
}
