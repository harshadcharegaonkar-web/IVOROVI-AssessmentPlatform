package com.ivorovi.insights.internal;

import com.ivorovi.platform.security.Authz;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Permission scoping for aggregates: a figure is computed only over records whose organization unit lies inside the
 * units where the viewer holds the permission (tenant-wide grants see everything; RLS already isolates tenants).
 */
@Component
class InsightsScope {

    private final Authz authz;
    private final JdbcClient jdbc;

    InsightsScope(Authz authz, JdbcClient jdbc) {
        this.authz = authz;
        this.jdbc = jdbc;
    }

    boolean any(String permission) {
        return authz.hasAnywhere(permission);
    }

    /** Roots the viewer holds the permission on; {@code null} = whole organization; empty = none. */
    List<String> roots(String permission) {
        if (authz.has(permission)) return null;
        List<String> roots = new ArrayList<>();
        for (UUID id : authz.orgUnitScopesWith(permission)) {
            jdbc.sql("SELECT path::text FROM org_unit WHERE id = ?").param(id).query(String.class).optional().ifPresent(roots::add);
        }
        return roots;
    }

    /**
     * Appends {@code AND <pathExpr> <@ roots} (nothing for tenant-wide holders) and an optional subtree filter.
     * Returns false when the viewer sees nothing.
     */
    boolean append(String permission, String pathExpr, UUID within, StringBuilder where, List<Object> params) {
        List<String> roots = roots(permission);
        if (roots != null && roots.isEmpty()) return false;
        if (roots != null) {
            where.append(" AND ").append(pathExpr).append(" <@ CAST(? AS ltree[])");
            params.add(roots.toArray(String[]::new));
        }
        appendWithin(pathExpr, within, where, params);
        return true;
    }

    /** Units-based visibility (staff and users are attached to units through assignments). */
    boolean appendExists(String permission, String unitsSql, UUID within, StringBuilder where, List<Object> params) {
        List<String> roots = roots(permission);
        if (roots != null && roots.isEmpty()) return false;
        if (roots != null || within != null) {
            where.append(" AND EXISTS (SELECT 1 FROM (").append(unitsSql).append(") h JOIN org_unit hu ON hu.id = h.unit WHERE true");
            if (roots != null) {
                where.append(" AND hu.path <@ CAST(? AS ltree[])");
                params.add(roots.toArray(String[]::new));
            }
            appendWithin("hu.path", within, where, params);
            where.append(")");
        }
        return true;
    }

    static void appendWithin(String pathExpr, UUID within, StringBuilder where, List<Object> params) {
        if (within == null) return;
        where.append(" AND ").append(pathExpr).append(" <@ (SELECT path FROM org_unit WHERE id = ?)");
        params.add(within);
    }
}
