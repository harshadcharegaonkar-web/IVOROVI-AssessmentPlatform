package com.ivorovi.platform.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Server-side authorization decisions. Exposed as bean {@code authz} for {@code @PreAuthorize} expressions, and
 * usable programmatically when the scope is only known after reading the request body.
 *
 * <p>Scope semantics (Phase 0 hierarchy: tenant → institution): a TENANT-scope grant covers the whole tenant
 * including every institution; an INSTITUTION-scope grant covers only that institution.
 */
@Component("authz")
public class Authz {

    /** Permission granted tenant-wide. */
    public boolean has(String permission) {
        return CurrentUser.get().map(p -> p.grants().stream().anyMatch(g ->
                g.permission().equals(permission) && g.scopeType() == ScopeType.TENANT
                        && g.scopeId().equals(p.tenantId()))).orElse(false);
    }

    /** Permission granted for the given institution (directly or through a tenant-wide grant). */
    public boolean hasInInstitution(String permission, UUID institutionId) {
        if (institutionId == null) return has(permission);
        return has(permission) || CurrentUser.get().map(p -> p.grants().stream().anyMatch(g ->
                g.permission().equals(permission) && g.scopeType() == ScopeType.INSTITUTION
                        && g.scopeId().equals(institutionId))).orElse(false);
    }

    /** Permission granted at any scope (used for navigation and list endpoints that filter by scope). */
    public boolean hasAnywhere(String permission) {
        return CurrentUser.get().map(p -> p.grants().stream().anyMatch(g -> g.permission().equals(permission)))
                .orElse(false);
    }

    /** Institutions where the permission is granted directly. Callers check {@link #has} first. */
    public Set<UUID> institutionsWith(String permission) {
        return CurrentUser.get().map(p -> p.grants().stream()
                .filter(g -> g.permission().equals(permission) && g.scopeType() == ScopeType.INSTITUTION)
                .map(Grant::scopeId).collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
    }

    public void require(String permission, UUID institutionIdOrNull) {
        if (!hasInInstitution(permission, institutionIdOrNull)) {
            throw new AccessDeniedException("Missing permission " + permission);
        }
    }
}
