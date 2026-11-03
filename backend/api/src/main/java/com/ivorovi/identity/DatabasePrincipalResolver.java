package com.ivorovi.identity;

import com.ivorovi.platform.security.Grant;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.security.PrincipalResolver;
import com.ivorovi.platform.security.ScopeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Resolves the caller's account and currently valid scoped grants. All queries are RLS-scoped to the tenant. */
@Component
class DatabasePrincipalResolver implements PrincipalResolver {

    private final JdbcClient jdbc;

    DatabasePrincipalResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IvoroviPrincipal> resolve(UUID tenantId, String subject) {
        record Account(UUID id, String email, String displayName) {}
        Optional<Account> account = jdbc.sql("""
                SELECT id, email, display_name FROM user_account
                WHERE tenant_id = ? AND external_subject = ? AND status = 'ACTIVE'""")
                .params(tenantId, subject)
                .query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name")))
                .optional();
        return account.map(a -> new IvoroviPrincipal(a.id(), tenantId, subject, a.email(), a.displayName(),
                grants(tenantId, a.id()), roles(tenantId, a.id()), nextBoundary(tenantId, a.id())));
    }

    /** Earliest future start or end of one of the user's role assignments (null when none). */
    private java.time.Instant nextBoundary(UUID tenantId, UUID userId) {
        java.sql.Timestamp t = jdbc.sql("""
                SELECT min(b) FROM (
                    SELECT valid_from AS b FROM role_assignment WHERE tenant_id = ? AND user_account_id = ? AND revoked_at IS NULL
                    UNION ALL
                    SELECT valid_to FROM role_assignment WHERE tenant_id = ? AND user_account_id = ? AND revoked_at IS NULL
                ) x WHERE b > now()""")
                .params(tenantId, userId, tenantId, userId).query((rs, n) -> rs.getTimestamp(1)).list()
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return t == null ? null : t.toInstant();
    }

    private List<String> roles(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                SELECT DISTINCT r.code FROM role_assignment ra JOIN role r ON r.id = ra.role_id
                WHERE ra.tenant_id = ? AND ra.user_account_id = ?
                  AND ra.revoked_at IS NULL AND r.status = 'ACTIVE' AND r.archived_at IS NULL AND ra.valid_from <= now() AND (ra.valid_to IS NULL OR ra.valid_to > now())
                ORDER BY r.code""")
                .params(tenantId, userId).query(String.class).list();
    }

    private List<Grant> grants(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                SELECT p.permission, r.code, ra.scope_type, ra.scope_id
                FROM role_assignment ra
                JOIN role r ON r.id = ra.role_id
                CROSS JOIN LATERAL unnest(r.permissions) AS p(permission)
                WHERE ra.tenant_id = ? AND ra.user_account_id = ?
                  AND ra.revoked_at IS NULL AND r.status = 'ACTIVE' AND r.archived_at IS NULL AND ra.valid_from <= now() AND (ra.valid_to IS NULL OR ra.valid_to > now())""")
                .params(tenantId, userId)
                .query((rs, n) -> new Grant(rs.getString(1), rs.getString(2), ScopeType.valueOf(rs.getString(3)),
                        rs.getObject(4, UUID.class)))
                .list();
    }
}
