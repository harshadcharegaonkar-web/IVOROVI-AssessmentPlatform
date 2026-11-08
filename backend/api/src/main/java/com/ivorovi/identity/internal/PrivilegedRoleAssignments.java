package com.ivorovi.identity.internal;

import com.ivorovi.approvals.ApprovalHandler;
import com.ivorovi.approvals.ApprovalService;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.security.PrincipalCache;
import com.ivorovi.platform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Activation of privileged role assignments (P2-08) through the 2.0 four-eyes framework. Eligibility of the approver and
 * the requester's authority are checked live (SQL, no principal cache) when the decision is made and again, with the
 * relevant assignment rows locked, when the assignment is created in the same transaction; any failed check invalidates
 * the approval. The database trigger {@code role_assignment_privileged_guard} repeats the essential checks.
 */
@Component
class PrivilegedRoleAssignments implements ApprovalHandler {

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Outbox outbox;
    private final PrincipalCache principals;

    PrivilegedRoleAssignments(JdbcClient jdbc, AuditLog audit, Outbox outbox, PrincipalCache principals) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.principals = principals;
    }

    @Override
    public String type() {
        return AssignmentService.PRIVILEGED_APPROVAL;
    }

    @Override
    public String label() {
        return "Privileged role assignment";
    }

    private record Target(UUID userId, UUID roleId, String scopeType, UUID scopeId, UUID unit, Instant validFrom, Instant validTo, String permissionsHash) {
        static Target of(ApprovalService.Request r) {
            Map<String, Object> p = r.payload();
            String scopeType = String.valueOf(p.get("scopeType"));
            UUID scopeId = UUID.fromString(String.valueOf(p.get("scopeId")));
            return new Target(UUID.fromString(String.valueOf(p.get("userId"))), UUID.fromString(String.valueOf(p.get("roleId"))), scopeType, scopeId,
                    "ORG_UNIT".equals(scopeType) ? scopeId : null, Instant.parse(String.valueOf(p.get("validFrom"))),
                    p.get("validTo") == null ? null : Instant.parse(String.valueOf(p.get("validTo"))), String.valueOf(p.get("permissionsHash")));
        }
    }

    private record RoleNow(String code, List<String> permissions, boolean usable) {}

    private Optional<RoleNow> role(UUID id) {
        return jdbc.sql("SELECT code, permissions, status = 'ACTIVE' AND archived_at IS NULL FROM role WHERE id = ?").param(id)
                .query((rs, n) -> new RoleNow(rs.getString(1), Arrays.asList((String[]) rs.getArray(2).getArray()), rs.getBoolean(3))).optional();
    }

    /** Same rule as {@code Authz.hasInOrgUnit} (organization-wide, the unit or an ancestor), evaluated live in SQL. */
    private boolean holds(UUID user, String permission, UUID unit) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT holds_permission(?, ?, ?, ?)").params(TenantContext.requireTenantId(), user, permission, unit)
                .query(Boolean.class).single());
    }

    static String permissionsHash(List<String> permissions) {
        try {
            String joined = String.join(",", permissions.stream().sorted().toList());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The approver is never the person being elevated and must hold every privileged permission of the role at the scope. */
    @Override
    public boolean mayDecide(ApprovalService.Request r, UUID decider) {
        Target t = Target.of(r);
        if (decider.equals(t.userId())) return false;
        Optional<RoleNow> role = role(t.roleId());
        if (role.isEmpty()) return false;
        return role.get().permissions().stream().filter(Permission.PRIVILEGED::contains).allMatch(p -> holds(decider, p, t.unit()));
    }

    /** The role must still be active with exactly the permissions the approver was shown. */
    @Override
    public boolean unchanged(ApprovalService.Request r) {
        Target t = Target.of(r);
        return role(t.roleId()).map(x -> x.usable() && permissionsHash(x.permissions()).equals(t.permissionsHash())).orElse(false);
    }

    @Override
    public void onApproved(ApprovalService.Request r) {
        Target t = Target.of(r);
        UUID approver = r.decidedBy();
        UUID requester = r.requestedBy();
        // lock the grants of both people: a concurrent revocation either commits first (and is seen) or waits for us
        jdbc.sql("SELECT id FROM role_assignment WHERE user_account_id IN (?, ?) AND revoked_at IS NULL FOR SHARE").params(approver, requester)
                .query(UUID.class).list();
        jdbc.sql("SELECT id FROM user_account WHERE id IN (?, ?, ?) FOR SHARE").params(approver, requester, t.userId()).query(UUID.class).list();
        RoleNow role = role(t.roleId()).orElseThrow(() -> new Invalidated("the role no longer exists"));
        if (!role.usable() || !permissionsHash(role.permissions()).equals(t.permissionsHash())) throw new Invalidated("the role changed after the request");
        for (String p : role.permissions().stream().filter(Permission.PRIVILEGED::contains).toList()) {
            if (!holds(approver, p, t.unit())) throw new Invalidated("the approver no longer holds " + p + " at this scope");
        }
        if (!holds(requester, Permission.ROLE_ASSIGN.key(), t.unit())) throw new Invalidated("the requester no longer holds role.assign at this scope");
        for (String p : role.permissions().stream().filter(p -> !Permission.PRIVILEGED.contains(p)).toList()) {
            if (!holds(requester, p, t.unit())) throw new Invalidated("the requester no longer holds " + p + " at this scope");
        }
        String status = jdbc.sql("SELECT status FROM user_account WHERE id = ?").param(t.userId()).query(String.class).optional()
                .orElseThrow(() -> new Invalidated("the account no longer exists"));
        if ("DEACTIVATED".equals(status)) throw new Invalidated("the account was deactivated");
        if (t.validTo() != null && !t.validTo().isAfter(Instant.now())) throw new Invalidated("the requested period has ended");
        boolean duplicate = jdbc.sql("""
                SELECT count(*) FROM role_assignment WHERE user_account_id = ? AND role_id = ? AND revoked_at IS NULL AND scope_type = ? AND scope_id = ?
                  AND (valid_to IS NULL OR valid_to > now())""").params(t.userId(), t.roleId(), t.scopeType(), t.scopeId()).query(Long.class).single() > 0;
        if (duplicate) throw new Invalidated("the role is already assigned at that scope");
        UUID id = Ids.newId();
        Instant from = t.validFrom().isBefore(Instant.now()) ? Instant.now() : t.validFrom();
        jdbc.sql("""
                INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id, valid_from, valid_to, created_by, approval_request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, TenantContext.requireTenantId(), t.userId(), t.roleId(), t.scopeType(), t.scopeId(), Timestamp.from(from),
                        t.validTo() == null ? null : Timestamp.from(t.validTo()), requester, r.id()).update();
        Map<String, String> d = new LinkedHashMap<>();
        d.put("userId", t.userId().toString());
        d.put("role", role.code());
        d.put("scope", t.unit() == null ? "TENANT" : "ORG_UNIT:" + t.unit());
        d.put("approvalId", r.id().toString());
        d.put("requestedBy", requester.toString());
        d.put("approvedBy", approver.toString());
        audit.recordSuccess("role.assigned", "role_assignment", id.toString(), d);
        audit.recordSuccess("role.privileged_assignment_activated", "role_assignment", id.toString(), d);
        outbox.append("role_assignment", id.toString(), "role.assigned", d);
        jdbc.sql("SELECT external_subject FROM user_account WHERE id = ? AND external_subject IS NOT NULL").param(t.userId()).query(String.class).list()
                .forEach(sub -> principals.evictSubject(TenantContext.requireTenantId(), sub));
    }
}
