package com.ivorovi.academics.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.cache.ValkeyJsonCache;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Shared rules of the academic module: every entity is owned by an organization unit (or the organization), managed
 * with a permission at that unit (inherited from ancestors), and readable by anyone with {@code academics.read} at
 * that unit, above it, or below it (a department head sees the program their course belongs to).
 */
@Component
public class AcademicScope {

    public record Unit(UUID id, String type, String code, String name, String path, String status) {}

    static final String READ = Permission.ACADEMICS_READ.key();

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final ValkeyJsonCache cache;

    AcademicScope(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, ValkeyJsonCache cache) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.cache = cache;
    }

    Optional<Unit> find(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.sql("SELECT id, unit_type, code, name, path::text, status FROM org_unit WHERE id = ?").param(id)
                .query((rs, n) -> new Unit(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6))).optional();
    }

    /** An active unit of the caller's organization, of one of the given types. */
    Unit activeUnit(UUID id, Set<String> types, String field, String what) {
        Unit u = find(id).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_" + field, "Choose " + what + " of your organization."));
        if (!types.contains(u.type())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_" + field, "Choose " + what + ".");
        }
        if (!"ACTIVE".equals(u.status())) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "inactive_" + field, u.name() + " is inactive.");
        return u;
    }

    /** Row lock serialising concurrent changes of one record (table names are constants of this module). */
    void lock(String table, UUID id) {
        if (!java.util.Set.of("program", "course", "subject", "academic_year", "academic_term", "batch", "subject_offering").contains(table)) {
            throw new IllegalArgumentException(table);
        }
        jdbc.sql("SELECT 1 FROM " + table + " WHERE id = ? FOR UPDATE").param(id).query(Integer.class).optional()
                .orElseThrow(() -> ApiException.notFound("Record"));
    }

    static boolean within(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + ".");
    }

    void requireManage(String permission, UUID unitId) {
        authz.require(permission, unitId);
    }

    boolean canManage(String permission, UUID unitId) {
        return authz.hasInOrgUnit(permission, unitId);
    }

    void requireAnyRead() {
        if (!authz.hasAnywhere(READ)) throw new AccessDeniedException("Missing permission " + READ);
    }

    /** Read access to something owned by {@code unitId} (null = organization-wide). */
    void requireRead(UUID unitId) {
        requireAnyRead();
        if (authz.has(READ)) return;
        if (unitId == null) return;                               // organization-wide calendar is visible to every reader
        Unit u = find(unitId).orElseThrow(() -> ApiException.notFound("Unit"));
        for (String root : readRoots()) {
            if (within(u.path(), root) || within(root, u.path())) return;
        }
        throw new AccessDeniedException("Outside academic scope");
    }

    /**
     * Visibility filter for lists: {@code pathExpr} is the owning unit's path (SQL); rows owned by the organization
     * ({@code pathExpr IS NULL}) are visible to every reader. Returns false when the caller sees nothing.
     */
    boolean appendVisibility(String pathExpr, StringBuilder where, List<Object> params) {
        if (!authz.hasAnywhere(READ)) return false;
        if (authz.has(READ)) return true;
        List<String> roots = readRoots();
        if (roots.isEmpty()) return false;
        where.append(" AND (").append(pathExpr).append(" IS NULL OR ").append(pathExpr).append(" <@ CAST(? AS ltree[]) OR ")
             .append(pathExpr).append(" @> CAST(? AS ltree[]))");
        String[] arr = roots.toArray(String[]::new);
        params.add(arr);
        params.add(arr);
        return true;
    }

    /** Stable key of the caller's read scope (for caching scope-filtered results). */
    String scopeKey() {
        if (authz.has(READ)) return "all";
        return String.join(",", new java.util.TreeSet<>(authz.orgUnitScopesWith(READ).stream().map(UUID::toString).toList()));
    }

    private List<String> readRoots() {
        List<String> roots = new ArrayList<>();
        for (UUID id : authz.orgUnitScopesWith(READ)) find(id).map(Unit::path).ifPresent(roots::add);
        return roots;
    }

    /** Audit + outbox + invalidation of the cached academic overview (after commit). */
    void changed(String action, String resourceType, UUID id, Map<String, String> details) {
        audit.recordSuccess(action, resourceType, id.toString(), details);
        outbox.append(resourceType, id.toString(), action, details);
        UUID tenant = TenantContext.requireTenantId();
        cache.afterCommit(() -> cache.increment(generationKey(tenant)));
    }

    long generation() {
        return cache.counter(generationKey(TenantContext.requireTenantId()));
    }

    static String generationKey(UUID tenant) {
        return "ivorovi:academics-gen:t:" + tenant;
    }

    static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
