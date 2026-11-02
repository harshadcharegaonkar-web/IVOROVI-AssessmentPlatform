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
                grants(tenantId, a.id())));
    }

    private List<Grant> grants(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                SELECT p.permission, r.code, ra.scope_type, ra.scope_id
                FROM role_assignment ra
                JOIN role r ON r.id = ra.role_id
                CROSS JOIN LATERAL unnest(r.permissions) AS p(permission)
                WHERE ra.tenant_id = ? AND ra.user_account_id = ?
                  AND ra.valid_from <= now() AND (ra.valid_to IS NULL OR ra.valid_to > now())""")
                .params(tenantId, userId)
                .query((rs, n) -> new Grant(rs.getString(1), rs.getString(2), ScopeType.valueOf(rs.getString(3)),
                        rs.getObject(4, UUID.class)))
                .list();
    }
}
