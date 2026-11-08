package com.ivorovi.identity.internal;

import com.ivorovi.platform.audit.AuditActor;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.security.PrincipalCache;
import com.ivorovi.platform.tenancy.TenantContext;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator bootstrap of the first holder of a privileged role (P2-08, runbook {@code privileged-role-bootstrap.md}).
 * Not an administrator function and not exposed over HTTP: it runs as a one-shot operator command with the schema
 * owner's database connection, which the trigger {@code role_assignment_privileged_guard} requires for bootstrap rows.
 * Refused (by this service and by the trigger) whenever anybody already holds a privileged permission of the role at or
 * above the scope; then the normal four-eyes request applies. Audited as {@code role.privileged_bootstrap} with the
 * operator as actor and the support ticket reference.
 */
@Component
public class PrivilegedRoleBootstrap {

    public record Command(UUID tenantId, String userEmail, String roleCode, UUID orgUnitId, String operator, String ticket, String reason) {}

    public record Result(UUID assignmentId, UUID userId, String roleCode, List<String> privileged) {}

    private final String url;
    private final String ownerUser;
    private final String ownerPassword;
    private final TransactionTemplate appTx;
    private final AuditLog audit;
    private final Outbox outbox;
    private final PrincipalCache principals;

    PrivilegedRoleBootstrap(@Value("${spring.flyway.url}") String url, @Value("${spring.flyway.user}") String ownerUser,
                            @Value("${spring.flyway.password}") String ownerPassword, TransactionTemplate appTx, AuditLog audit, Outbox outbox,
                            PrincipalCache principals) {
        this.url = url;
        this.ownerUser = ownerUser;
        this.ownerPassword = ownerPassword;
        this.appTx = appTx;
        this.audit = audit;
        this.outbox = outbox;
        this.principals = principals;
    }

    /** Operator identity recorded as the audit actor (no application account exists for operators). */
    static final class OperatorAuthentication extends AbstractAuthenticationToken implements AuditActor {
        private final String operator;

        OperatorAuthentication(String operator) {
            super(List.of());
            this.operator = operator;
            setAuthenticated(false);
        }

        @Override public Object getCredentials() { return ""; }
        @Override public Object getPrincipal() { return operator; }
        @Override public UUID auditActorId() { return null; }
        @Override public String auditActorSubject() { return "operator:" + operator; }
    }

    private static String required(String v, String name, int min, int max) {
        if (v == null || v.strip().length() < min || v.strip().length() > max) {
            throw new IllegalArgumentException(name + " is required (" + min + "–" + max + " characters)");
        }
        return v.strip();
    }

    public Result bootstrap(Command c) {
        String operator = required(c.operator(), "operator", 2, 100);
        String ticket = required(c.ticket(), "ticket", 3, 200);
        String reason = required(c.reason(), "reason", 5, 500);
        DriverManagerDataSource ds = new DriverManagerDataSource(url, ownerUser, ownerPassword);
        JdbcClient owner = JdbcClient.create(ds);
        TransactionTemplate ownerTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Result result = ownerTx.execute(s -> {
            String tenantStatus = owner.sql("SELECT status FROM tenant WHERE id = ?").param(c.tenantId()).query(String.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("unknown organization"));
            if (!List.of("ACTIVE", "TRIAL").contains(tenantStatus)) throw new IllegalStateException("the organization is not active");
            UUID user = owner.sql("SELECT id FROM user_account WHERE tenant_id = ? AND lower(email) = lower(?) AND status = 'ACTIVE'")
                    .params(c.tenantId(), required(c.userEmail(), "user email", 3, 320)).query(UUID.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("no active account with that email in the organization"));
            record R(UUID id, List<String> perms) {}
            R role = owner.sql("""
                    SELECT id, permissions FROM role WHERE code = ? AND (tenant_id IS NULL OR tenant_id = ?) AND status = 'ACTIVE' AND archived_at IS NULL
                    ORDER BY tenant_id NULLS LAST LIMIT 1""").params(required(c.roleCode(), "role", 2, 60), c.tenantId())
                    .query((rs, n) -> new R(rs.getObject(1, UUID.class), Arrays.asList((String[]) rs.getArray(2).getArray()))).optional()
                    .orElseThrow(() -> new IllegalArgumentException("unknown or inactive role"));
            List<String> privileged = role.perms().stream().filter(Permission.PRIVILEGED::contains).sorted().toList();
            if (privileged.isEmpty()) throw new IllegalArgumentException("the role has no privileged permission; assign it normally");
            if (c.orgUnitId() != null && owner.sql("SELECT count(*) FROM org_unit WHERE id = ? AND tenant_id = ?").params(c.orgUnitId(), c.tenantId())
                    .query(Long.class).single() == 0) throw new IllegalArgumentException("unknown unit of the organization");
            for (String p : privileged) {
                long holders = owner.sql("SELECT count(*) FROM user_account u WHERE u.tenant_id = ? AND holds_permission(?, u.id, ?, ?)")
                        .params(c.tenantId(), c.tenantId(), p, c.orgUnitId()).query(Long.class).single();
                if (holders > 0) throw new IllegalStateException(p + " is already held at or above this scope: use a four-eyes approval instead");
            }
            UUID id = Ids.newId();
            owner.sql("""
                    INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id, bootstrap_reference)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, c.tenantId(), user, role.id(), c.orgUnitId() == null ? "TENANT" : "ORG_UNIT",
                            c.orgUnitId() == null ? c.tenantId() : c.orgUnitId(), ticket).update();
            return new Result(id, user, c.roleCode(), privileged);
        });
        // audit and notify as the operator, in the organization (application connection, hash-chained audit trail)
        var previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            SecurityContextHolder.getContext().setAuthentication(new OperatorAuthentication(operator));
            TenantContext.runInTenant(c.tenantId(), () -> appTx.executeWithoutResult(s -> {
                Map<String, String> d = new LinkedHashMap<>();
                d.put("userId", result.userId().toString());
                d.put("role", result.roleCode());
                d.put("scope", c.orgUnitId() == null ? "TENANT" : "ORG_UNIT:" + c.orgUnitId());
                d.put("privileged", String.join(",", result.privileged()));
                d.put("operator", operator);
                d.put("ticket", ticket);
                d.put("reason", reason);
                audit.recordSuccess("role.privileged_bootstrap", "role_assignment", result.assignmentId().toString(), d);
                outbox.append("role_assignment", result.assignmentId().toString(), "role.assigned", d);
            }));
            principals.evictTenant(c.tenantId());
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
        return result;
    }
}
