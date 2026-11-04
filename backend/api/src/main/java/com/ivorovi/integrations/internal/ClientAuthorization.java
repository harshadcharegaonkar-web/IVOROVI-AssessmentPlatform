package com.ivorovi.integrations.internal;

import com.ivorovi.platform.security.Grant;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.security.OrgScopeResolver;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.security.PrincipalDirectory;
import com.ivorovi.platform.security.ScopeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * KI-59: the effective permissions of an API client, computed from CURRENT state on every request — never from a stored
 * copy. Delegated clients: registered scopes ∩ the owner's current grants (owner must be ACTIVE). Service clients:
 * registered scopes ∩ the service identity's current role grants ∩ its approved envelope (identity must be ACTIVE).
 * Both: read-only assignable permissions only, narrowed to the client's unit. Call with the tenant context bound.
 */
@Component
public class ClientAuthorization {

    static final String ROLE = "API_CLIENT";

    /** What a client may do right now. {@code denial} non-null = the key must be refused (reason code). */
    public record Effective(List<Grant> grants, List<String> effectiveScopes, Map<String, String> lostScopes, String denial) {
        static Effective denied(String reason, List<String> scopes) {
            Map<String, String> lost = new LinkedHashMap<>();
            for (String s : scopes) lost.put(s, reason);
            return new Effective(List.of(), List.of(), lost, reason);
        }
    }

    /** The parts of a client row that authorization depends on. */
    public record Subject(UUID clientId, String kind, UUID ownerUserId, UUID serviceIdentityId, List<String> scopes, UUID orgUnitId) {}

    private final PrincipalDirectory principals;
    private final OrgScopeResolver units;
    private final JdbcClient jdbc;

    ClientAuthorization(PrincipalDirectory principals, OrgScopeResolver units, JdbcClient jdbc) {
        this.principals = principals;
        this.units = units;
        this.jdbc = jdbc;
    }

    public Effective evaluate(UUID tenant, Subject c) {
        List<Grant> source;
        Set<String> envelope = null;
        if ("SERVICE".equals(c.kind())) {
            Optional<Identity> id = identity(c.serviceIdentityId());
            if (id.isEmpty() || !"ACTIVE".equals(id.get().status())) return Effective.denied("api_client_identity_inactive", c.scopes());
            envelope = Set.copyOf(id.get().envelope());
            source = serviceGrants(tenant, c.serviceIdentityId(), id.get().envelope());
        } else {
            Optional<IvoroviPrincipal> owner = c.ownerUserId() == null ? Optional.empty() : principals.activeUser(tenant, c.ownerUserId());
            if (owner.isEmpty()) return Effective.denied("api_client_owner_inactive", c.scopes());
            source = owner.get().grants();
        }
        return intersect(tenant, c, source, envelope, "SERVICE".equals(c.kind()) ? "role_missing" : "owner_lacks", false);
    }

    /**
     * Strict check for reassignment: the candidate owner must hold every scope over the client's WHOLE unit (organization
     * when the client is not unit-restricted), not just part of it.
     */
    public Effective evaluateForOwner(UUID tenant, Subject c, UUID candidateOwner) {
        Optional<IvoroviPrincipal> owner = principals.activeUser(tenant, candidateOwner);
        if (owner.isEmpty()) return Effective.denied("api_client_owner_inactive", c.scopes());
        return intersect(tenant, c, owner.get().grants(), null, "owner_lacks", true);
    }

    private Effective intersect(UUID tenant, Subject c, List<Grant> source, Set<String> envelope, String missingReason, boolean fullCoverage) {
        Map<UUID, Optional<String>> paths = new HashMap<>();
        List<Grant> grants = new ArrayList<>();
        List<String> effective = new ArrayList<>();
        Map<String, String> lost = new LinkedHashMap<>();
        for (String scope : c.scopes()) {
            Optional<Permission> p = Permission.fromKey(scope);
            if (p.isEmpty() || !ApiClientService.assignable(p.get())) { lost.put(scope, "not_allowed"); continue; }
            if (envelope != null && !envelope.contains(scope)) { lost.put(scope, "envelope"); continue; }
            List<Grant> forScope = new ArrayList<>();
            for (Grant g : source) {
                if (!g.permission().equals(scope)) continue;
                Grant narrowed = narrow(tenant, g, c.orgUnitId(), paths);
                if (narrowed == null) continue;
                if (fullCoverage && !covers(narrowed, tenant, c.orgUnitId())) continue;
                if (!forScope.contains(narrowed)) forScope.add(narrowed);
            }
            if (forScope.isEmpty()) { lost.put(scope, missingReason); continue; }
            grants.addAll(forScope);
            effective.add(scope);
        }
        return new Effective(grants, effective, lost, null);
    }

    /** The part of a grant that lies inside the client's unit (null = nothing). */
    private Grant narrow(UUID tenant, Grant g, UUID clientUnit, Map<UUID, Optional<String>> paths) {
        if (g.scopeType() == ScopeType.TENANT) {
            if (!g.scopeId().equals(tenant)) return null;
            return clientUnit == null ? new Grant(g.permission(), ROLE, ScopeType.TENANT, tenant) : new Grant(g.permission(), ROLE, ScopeType.ORG_UNIT, clientUnit);
        }
        if (clientUnit == null) return new Grant(g.permission(), ROLE, ScopeType.ORG_UNIT, g.scopeId());
        Optional<String> grantPath = paths.computeIfAbsent(g.scopeId(), units::pathOf);
        Optional<String> clientPath = paths.computeIfAbsent(clientUnit, units::pathOf);
        if (grantPath.isEmpty() || clientPath.isEmpty()) return null;
        if (within(clientPath.get(), grantPath.get())) return new Grant(g.permission(), ROLE, ScopeType.ORG_UNIT, clientUnit);
        if (within(grantPath.get(), clientPath.get())) return new Grant(g.permission(), ROLE, ScopeType.ORG_UNIT, g.scopeId());
        return null;
    }

    /** The narrowed grant spans the client's whole scope (organization, or exactly the client's unit). */
    private static boolean covers(Grant g, UUID tenant, UUID clientUnit) {
        return clientUnit == null ? g.scopeType() == ScopeType.TENANT && g.scopeId().equals(tenant)
                : g.scopeType() == ScopeType.ORG_UNIT && g.scopeId().equals(clientUnit);
    }

    private static boolean within(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + ".");
    }

    record Identity(String status, List<String> envelope) {}

    private Optional<Identity> identity(UUID id) {
        return jdbc.sql("SELECT status, scope_envelope FROM service_identity WHERE id = ?").param(id)
                .query((rs, n) -> new Identity(rs.getString(1), ApiClientService.strings(rs.getArray(2)))).optional();
    }

    /** Current grants of a service identity from its unrevoked assignments of ACTIVE roles, limited to its envelope. */
    List<Grant> serviceGrants(UUID tenant, UUID identityId, List<String> envelope) {
        return jdbc.sql("""
                SELECT p.permission, r.code, sra.scope_type, sra.scope_id
                FROM service_role_assignment sra JOIN role r ON r.id = sra.role_id
                CROSS JOIN LATERAL unnest(r.permissions) AS p(permission)
                WHERE sra.service_identity_id = ? AND sra.revoked_at IS NULL AND r.status = 'ACTIVE' AND r.archived_at IS NULL
                  AND p.permission = ANY (CAST(? AS text[]))""")
                .params(identityId, envelope.toArray(String[]::new))
                .query((rs, n) -> new Grant(rs.getString(1), rs.getString(2), ScopeType.valueOf(rs.getString(3)), rs.getObject(4, UUID.class))).list();
    }
}
