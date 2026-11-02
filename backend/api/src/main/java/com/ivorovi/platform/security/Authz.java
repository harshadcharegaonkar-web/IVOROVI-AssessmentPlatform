package com.ivorovi.platform.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Server-side authorization decisions. Exposed as bean {@code authz} for {@code @PreAuthorize} expressions, and
 * usable programmatically when the scope is only known after reading the request body.
 *
 * <p>Scope semantics: a TENANT grant covers the whole organization; an ORG_UNIT grant covers that unit and its
 * entire subtree (university → colleges → campuses → departments), resolved through materialised paths.
 */
@Component("authz")
public class Authz {

    private final OrgScopeResolver scopes;

    Authz(OrgScopeResolver scopes) {
        this.scopes = scopes;
    }

    /** Permission granted tenant-wide. */
    public boolean has(String permission) {
        return CurrentUser.get().map(p -> p.grants().stream().anyMatch(g ->
                g.permission().equals(permission) && g.scopeType() == ScopeType.TENANT
                        && g.scopeId().equals(p.tenantId()))).orElse(false);
    }

    /** Permission granted for the given unit: tenant-wide, on the unit itself, or on any of its ancestors. */
    public boolean hasInOrgUnit(String permission, UUID orgUnitId) {
        if (orgUnitId == null) return has(permission);
        if (has(permission)) return true;
        Optional<IvoroviPrincipal> principal = CurrentUser.get();
        if (principal.isEmpty()) return false;
        Set<UUID> grantRoots = orgUnitScopesWith(permission);
        if (grantRoots.isEmpty()) return false;
        if (grantRoots.contains(orgUnitId)) return true;
        Optional<String> target = scopes.pathOf(orgUnitId);
        if (target.isEmpty()) return false;
        for (UUID root : grantRoots) {
            Optional<String> rootPath = scopes.pathOf(root);
            if (rootPath.isPresent() && isWithin(target.get(), rootPath.get())) return true;
        }
        return false;
    }

    /** Permission granted at any scope (navigation, list endpoints that filter by scope). */
    public boolean hasAnywhere(String permission) {
        return CurrentUser.get().map(p -> p.grants().stream().anyMatch(g -> g.permission().equals(permission)))
                .orElse(false);
    }

    /** Units whose subtrees the permission is granted on directly (callers check {@link #has} first). */
    public Set<UUID> orgUnitScopesWith(String permission) {
        return CurrentUser.get().map(p -> p.grants().stream()
                .filter(g -> g.permission().equals(permission) && g.scopeType() == ScopeType.ORG_UNIT)
                .map(Grant::scopeId).collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
    }

    public void require(String permission, UUID orgUnitIdOrNull) {
        if (!hasInOrgUnit(permission, orgUnitIdOrNull)) {
            throw new AccessDeniedException("Missing permission " + permission);
        }
    }

    /** {@code path} equals {@code ancestor} or lies below it (label-boundary aware). */
    static boolean isWithin(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + ".");
    }
}
