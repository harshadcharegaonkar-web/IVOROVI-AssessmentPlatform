package com.ivorovi.identity.internal;

import com.ivorovi.platform.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Invariants shared by role assignment and account lifecycle. */
final class RoleRules {

    /** System roles that cannot be granted from the administration UI. */
    static final java.util.Set<String> NOT_ASSIGNABLE = java.util.Set.of("PLATFORM_SUPER_ADMIN", "STUDENT");

    private RoleRules() {}

    /**
     * An organization must always keep at least one active user holding ORG_ADMIN for the whole organization.
     * Called before a change that would remove {@code userId}'s organization-administrator access.
     */
    static void requireNotLastOrganizationAdmin(JdbcClient jdbc, UUID userId) {
        // serialise concurrent removals within the tenant
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(current_setting('app.tenant_id'), 7042))").query((rs, n) -> 1).list();
        long others = jdbc.sql("""
                SELECT count(DISTINCT ra.user_account_id) FROM role_assignment ra
                JOIN role r ON r.id = ra.role_id JOIN user_account u ON u.id = ra.user_account_id
                WHERE r.code = 'ORG_ADMIN' AND ra.scope_type = 'TENANT' AND ra.revoked_at IS NULL
                  AND ra.valid_from <= now() AND (ra.valid_to IS NULL OR ra.valid_to > now())
                  AND u.status = 'ACTIVE' AND ra.user_account_id <> ?""").param(userId).query(Long.class).single();
        boolean isAdmin = jdbc.sql("""
                SELECT count(*) FROM role_assignment ra JOIN role r ON r.id = ra.role_id
                WHERE r.code = 'ORG_ADMIN' AND ra.scope_type = 'TENANT' AND ra.revoked_at IS NULL AND ra.user_account_id = ?
                  AND (ra.valid_to IS NULL OR ra.valid_to > now())""").param(userId).query(Long.class).single() > 0;
        if (isAdmin && others == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "last_organization_admin",
                    "This is the last active organization administrator. Assign the role to someone else first.");
        }
    }
}
