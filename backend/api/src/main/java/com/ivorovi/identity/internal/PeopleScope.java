package com.ivorovi.identity.internal;

import com.ivorovi.platform.security.Authz;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Scope rules for people. A user's <em>units</em> are the units of their organizational assignments (direct or
 * through a linked staff profile) and of their active role assignments; {@code null} stands for the organization
 * as a whole. A user is visible to whoever holds the read permission on any of those units, and manageable only by
 * whoever holds the manage permission on <em>all</em> of them, so a scoped administrator can never act on someone
 * who also belongs to (or holds a role in) a part of the organization outside their scope.
 */
@Component
public class PeopleScope {

    /** Units of user {@code ?} (bind the user id three times). */
    static final String USER_UNITS = """
            SELECT oa.org_unit_id AS unit FROM org_assignment oa WHERE oa.user_account_id = ?
            UNION SELECT oa.org_unit_id FROM org_assignment oa JOIN staff_profile sp ON sp.id = oa.staff_profile_id
                  WHERE sp.user_account_id = ?
            UNION SELECT CASE WHEN ra.scope_type = 'ORG_UNIT' THEN ra.scope_id END FROM role_assignment ra
                  WHERE ra.user_account_id = ? AND ra.revoked_at IS NULL""";

    /** Units of staff profile {@code ?}. */
    static final String STAFF_UNITS = "SELECT oa.org_unit_id AS unit FROM org_assignment oa WHERE oa.staff_profile_id = ?";

    private final JdbcClient jdbc;
    private final Authz authz;

    PeopleScope(JdbcClient jdbc, Authz authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    public List<UUID> userUnits(UUID userId) {
        return jdbc.sql(USER_UNITS).params(userId, userId, userId).query((rs, n) -> rs.getObject(1, UUID.class)).list();
    }

    public List<UUID> staffUnits(UUID staffId) {
        return jdbc.sql(STAFF_UNITS).param(staffId).query((rs, n) -> rs.getObject(1, UUID.class)).list();
    }

    public boolean canRead(String permission, List<UUID> units) {
        if (authz.has(permission)) return true;
        return units.stream().filter(Objects::nonNull).anyMatch(u -> authz.hasInOrgUnit(permission, u));
    }

    public boolean canManage(String permission, List<UUID> units) {
        if (units.isEmpty()) return authz.has(permission);
        return units.stream().allMatch(u -> authz.hasInOrgUnit(permission, u));
    }

    /**
     * Appends a visibility filter for list queries. {@code unitsSql} selects the units of the row aliased by the
     * caller (it must reference the outer row, e.g. {@code u.id}). Returns false when the caller sees nothing.
     */
    boolean appendVisibility(String permission, String unitsSql, StringBuilder where, List<Object> params) {
        if (authz.has(permission)) return true;
        List<String> roots = new ArrayList<>();
        for (UUID root : authz.orgUnitScopesWith(permission)) {
            jdbc.sql("SELECT path::text FROM org_unit WHERE id = ?").param(root).query(String.class).optional().ifPresent(roots::add);
        }
        if (roots.isEmpty()) return false;
        where.append(" AND EXISTS (SELECT 1 FROM (").append(unitsSql)
             .append(") h JOIN org_unit ou ON ou.id = h.unit WHERE ou.path <@ CAST(? AS ltree[]))");
        params.add(roots.toArray(String[]::new));
        return true;
    }

    /** {@link #USER_UNITS} correlated to the outer alias {@code u}. */
    static final String USER_UNITS_OF_U = """
            SELECT oa.org_unit_id AS unit FROM org_assignment oa WHERE oa.user_account_id = u.id
            UNION SELECT oa.org_unit_id FROM org_assignment oa JOIN staff_profile sp ON sp.id = oa.staff_profile_id
                  WHERE sp.user_account_id = u.id
            UNION SELECT CASE WHEN ra.scope_type = 'ORG_UNIT' THEN ra.scope_id END FROM role_assignment ra
                  WHERE ra.user_account_id = u.id AND ra.revoked_at IS NULL""";

    /** {@link #STAFF_UNITS} correlated to the outer alias {@code s}. */
    static final String STAFF_UNITS_OF_S = "SELECT oa.org_unit_id AS unit FROM org_assignment oa WHERE oa.staff_profile_id = s.id";
}
