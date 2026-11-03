package com.ivorovi.identity.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.security.PrincipalCache;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organizational assignments (membership of users and staff in units) and role assignments (permissions at a
 * scope). Role assignment follows the anti-escalation rule: the granter must hold every permission of the role at
 * the target scope, may not change their own assignments, and the last organization administrator stays.
 */
@Service
public class AssignmentService {

    public enum Subject { USER, STAFF }

    @Schema(name = "OrgAssignment")
    public record OrgAssignmentView(UUID id, UUID orgUnitId, String orgUnitName, String orgUnitType, boolean primary,
                                    String title, LocalDate startsOn, LocalDate endsOn, String source) {}

    @Schema(name = "RoleAssignment")
    public record RoleAssignmentView(UUID id, UUID roleId, String roleCode, String roleName, String scopeType, UUID orgUnitId,
                                     String scopeName, Instant validFrom, Instant validTo, Instant createdAt, String grantedByName,
                                     Instant revokedAt, String revokedByName, String revocationReason, boolean active,
                                     String roleStatus, boolean systemRole) {}

    @Schema(name = "AssignableRole")
    public record RoleView(UUID id, String code, String name, String description, List<String> permissions, boolean system,
                           UUID scopeOrgUnitId, String scopeName, boolean organizationOnly) {}

    @Schema(name = "RoleAssignmentOverview")
    public record OverviewRow(UUID assignmentId, UUID userId, String userName, String email, UUID roleId, String roleName,
                              boolean systemRole, String roleStatus, String scopeType, UUID orgUnitId, String scopeName,
                              Instant validFrom, Instant validTo, String grantedByName, Instant createdAt) {}

    @Schema(name = "EffectivePermission")
    public record EffectivePermission(String permission, String description, List<PermissionScope> scopes) {}

    @Schema(name = "PermissionScope")
    public record PermissionScope(String scopeType, UUID orgUnitId, String scopeName, List<String> viaRoles) {}

    @Schema(name = "UserAccess")
    public record Access(UserService.AccountStatus status, UserService.InvitationState invitationStatus,
                         List<OrgAssignmentView> assignments, List<RoleAssignmentView> roles, List<EffectivePermission> permissions) {}

    public record OrgAssignmentCommand(UUID orgUnitId, String title, boolean primary, LocalDate startsOn, LocalDate endsOn) {}

    public record RoleAssignmentCommand(UUID roleId, UUID orgUnitId, Instant validFrom, Instant validTo) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final PeopleScope scope;
    private final AuditLog audit;
    private final Outbox outbox;
    private final PrincipalCache principals;
    private final UserService users;

    AssignmentService(JdbcClient jdbc, Authz authz, PeopleScope scope, AuditLog audit, Outbox outbox,
                      PrincipalCache principals, UserService users) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.scope = scope;
        this.audit = audit;
        this.outbox = outbox;
        this.principals = principals;
        this.users = users;
    }

    // ------------------------------------------------------------------------------------ organizational assignments

    @Transactional(readOnly = true)
    public List<OrgAssignmentView> orgAssignments(Subject subject, UUID id) {
        requireRead(subject, id);
        return listOrgAssignments(subject, id);
    }

    @Transactional
    public OrgAssignmentView addOrgAssignment(Subject subject, UUID id, OrgAssignmentCommand c) {
        requireManage(subject, id);
        String perm = managePermission(subject);
        authz.require(perm, c.orgUnitId());
        if (c.orgUnitId() != null) {
            boolean active = jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ? AND status = 'ACTIVE'").param(c.orgUnitId())
                    .query(Long.class).single() > 0;
            if (!active) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_org_unit", "Choose an active unit of your organization.");
        }
        if (c.startsOn() != null && c.endsOn() != null && c.endsOn().isBefore(c.startsOn())) {
            throw ApiException.badRequest("invalid_dates", "The end date must not be before the start date.");
        }
        String col = column(subject);
        if (c.primary()) jdbc.sql("UPDATE org_assignment SET is_primary = false WHERE " + col + " = ?").param(id).update();
        boolean first = jdbc.sql("SELECT count(*) FROM org_assignment WHERE " + col + " = ?").param(id).query(Long.class).single() == 0;
        UUID aid = Ids.newId();
        try {
            jdbc.sql("INSERT INTO org_assignment (id, tenant_id, " + col + ", org_unit_id, is_primary, title, starts_on, ends_on, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(aid, TenantContext.requireTenantId(), id, c.orgUnitId(), c.primary() || first,
                            UserService.optional(c.title(), 100), date(c.startsOn()), date(c.endsOn()), CurrentUser.require().userId())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("assignment_exists", "This person is already assigned to that unit.");
        }
        auditAssignment("org_assignment.added", subject, id, aid, c.orgUnitId());
        return listOrgAssignments(subject, id).stream().filter(a -> a.id().equals(aid)).findFirst().orElseThrow();
    }

    @Transactional
    public void removeOrgAssignment(Subject subject, UUID id, UUID assignmentId) {
        requireManage(subject, id);
        String col = column(subject);
        record A(UUID unit, boolean primary) {}
        A a = jdbc.sql("SELECT org_unit_id, is_primary FROM org_assignment WHERE id = ? AND " + col + " = ?").params(assignmentId, id)
                .query((rs, n) -> new A(rs.getObject(1, UUID.class), rs.getBoolean(2))).optional()
                .orElseThrow(() -> ApiException.notFound("Assignment"));
        authz.require(managePermission(subject), a.unit());
        long count = jdbc.sql("SELECT count(*) FROM org_assignment WHERE " + col + " = ?").param(id).query(Long.class).single();
        if (count <= 1) throw ApiException.conflict("last_assignment", "A person needs at least one organizational assignment. Add the new one first.");
        jdbc.sql("DELETE FROM org_assignment WHERE id = ?").param(assignmentId).update();
        if (a.primary()) {
            jdbc.sql("UPDATE org_assignment SET is_primary = true WHERE id = (SELECT id FROM org_assignment WHERE " + col
                    + " = ? ORDER BY created_at LIMIT 1)").param(id).update();
        }
        auditAssignment("org_assignment.removed", subject, id, assignmentId, a.unit());
    }

    @Transactional
    public void makePrimary(Subject subject, UUID id, UUID assignmentId) {
        requireManage(subject, id);
        String col = column(subject);
        int exists = jdbc.sql("SELECT count(*) FROM org_assignment WHERE id = ? AND " + col + " = ?").params(assignmentId, id)
                .query(Integer.class).single();
        if (exists == 0) throw ApiException.notFound("Assignment");
        jdbc.sql("UPDATE org_assignment SET is_primary = false WHERE " + col + " = ?").param(id).update();
        jdbc.sql("UPDATE org_assignment SET is_primary = true WHERE id = ?").param(assignmentId).update();
        auditAssignment("org_assignment.primary_changed", subject, id, assignmentId, null);
    }

    // ------------------------------------------------------------------------------------------ role assignments

    @Transactional(readOnly = true)
    public List<RoleView> assignableRoles() {
        if (!authz.hasAnywhere(Permission.ROLE_READ.key())) throw new AccessDeniedException("Missing permission role.read");
        return jdbc.sql("""
                SELECT r.id, r.code, r.name, r.description, r.permissions, r.system_role, r.scope_org_unit_id, ou.name
                FROM role r LEFT JOIN org_unit ou ON ou.id = r.scope_org_unit_id
                WHERE r.status = 'ACTIVE' AND r.archived_at IS NULL AND NOT (r.code = ANY(?)) ORDER BY r.system_role DESC, r.name""")
                .param(RoleRules.NOT_ASSIGNABLE.toArray(String[]::new))
                .query((rs, n) -> {
                    List<String> perms = Arrays.stream((String[]) rs.getArray(5).getArray()).sorted().toList();
                    return new RoleView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), perms,
                            rs.getBoolean(6), rs.getObject(7, UUID.class), rs.getObject(7) == null ? "Whole organization" : rs.getString(8),
                            organizationOnly(perms));
                })
                .list();
    }

    /** All current role assignments of users the caller can see (role assignment overview). */
    @Transactional(readOnly = true)
    public UserService.Page<OverviewRow> overview(String q, UUID roleId, UUID orgUnitId, int page, int size) {
        if (!authz.hasAnywhere(Permission.ROLE_READ.key())) throw new AccessDeniedException("Missing permission role.read");
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ra.revoked_at IS NULL AND (ra.valid_to IS NULL OR ra.valid_to > now()) AND NOT (r.code = ANY(?))");
        params.add(RoleRules.NOT_ASSIGNABLE.toArray(String[]::new));
        if (roleId != null) { where.append(" AND ra.role_id = ?"); params.add(roleId); }
        if (orgUnitId != null) {
            where.append(" AND ra.scope_type = 'ORG_UNIT' AND ou.path <@ (SELECT path FROM org_unit WHERE id = ?)");
            params.add(orgUnitId);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.display_name ILIKE ? OR u.email ILIKE ? OR r.name ILIKE ?)");
            String like = UserService.like(q);
            params.add(like); params.add(like); params.add(like);
        }
        if (!scope.appendVisibility(UserService.READ, PeopleScope.USER_UNITS_OF_U, where, params)) return new UserService.Page<>(List.of(), 0);
        String from = " FROM role_assignment ra JOIN user_account u ON u.id = ra.user_account_id JOIN role r ON r.id = ra.role_id"
                + " LEFT JOIN org_unit ou ON ra.scope_type = 'ORG_UNIT' AND ou.id = ra.scope_id LEFT JOIN user_account g ON g.id = ra.created_by";
        long total = jdbc.sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((long) page * size);
        List<OverviewRow> rows = jdbc.sql("SELECT ra.id, u.id, u.display_name, u.email, r.id, r.name, r.system_role, r.status, ra.scope_type,"
                        + " ou.id, ou.name, ra.valid_from, ra.valid_to, g.display_name, ra.created_at" + from + where
                        + " ORDER BY u.display_name, r.name, ra.id LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, n) -> new OverviewRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class),
                        "TENANT".equals(rs.getString(9)) ? "Whole organization" : rs.getString(11),
                        UserService.instant(rs, "valid_from"), UserService.instant(rs, "valid_to"), rs.getString(14), UserService.instant(rs, "created_at")))
                .list();
        return new UserService.Page<>(rows, total);
    }

    private static boolean organizationOnly(List<String> perms) {
        return perms.stream().map(Permission::fromKey).flatMap(java.util.Optional::stream)
                .anyMatch(p -> p.scope() == Permission.ScopeRule.ORGANIZATION);
    }

    @Transactional(readOnly = true)
    public List<RoleAssignmentView> roleAssignments(UUID userId) {
        users.requireVisible(userId);
        return listRoleAssignments(userId, true);
    }

    @Transactional
    public RoleAssignmentView assignRole(UUID userId, RoleAssignmentCommand c) {
        UserService.Row user = users.requireVisible(userId);
        requireNotSelf(userId);
        if (user.summary().status() == UserService.AccountStatus.DEACTIVATED) {
            throw ApiException.conflict("user_deactivated", "Activate the account before assigning roles.");
        }
        // serialise with archive/deactivation of the same custom role (system roles are immutable)
        jdbc.sql("SELECT id FROM role WHERE id = ? AND NOT system_role FOR SHARE").param(c.roleId()).query(UUID.class).list();
        Role role = role(c.roleId());
        // accounts created for student profiles hold the STUDENT role only (increment 1.5)
        if (!StudentAccountService.STUDENT_ROLE.equals(role.id())
                && jdbc.sql("SELECT count(*) FROM student WHERE user_account_id = ?").param(userId).query(Long.class).single() > 0) {
            audit.recordDenied("role.assignment_denied", Map.of("reason", "student_account", "userId", userId.toString(), "roleId", role.id().toString()));
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "student_account_role_restricted",
                    "This account belongs to a student and can only hold the Student role.");
        }
        if (!"ACTIVE".equals(role.status())) {
            throw ApiException.conflict("role_inactive", "This role is deactivated. Reactivate it before assigning it.");
        }
        if (role.scopeOrgUnitId() != null) {
            boolean within = c.orgUnitId() != null && jdbc.sql("""
                    SELECT count(*) FROM org_unit t JOIN org_unit s ON s.id = ? WHERE t.id = ? AND t.path <@ s.path""")
                    .params(role.scopeOrgUnitId(), c.orgUnitId()).query(Long.class).single() > 0;
            if (!within) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "role_scope_mismatch",
                        "This role is limited to " + role.scopeName() + " and can only be assigned there or below.");
            }
        }
        // custom roles with organization-only permissions only make sense for the whole organization (system roles keep
        // their historical shape: their organization-only permissions are simply not effective below that level)
        if (c.orgUnitId() != null && !role.system() && organizationOnly(role.permissions())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "organization_only_role",
                    "This role contains organization-wide permissions and can only be assigned for the whole organization.");
        }
        requireCanGrant(role, c.orgUnitId(), "role.assignment_denied", userId);
        Instant from = c.validFrom() == null ? Instant.now() : c.validFrom();
        if (c.validTo() != null && !c.validTo().isAfter(from)) {
            throw ApiException.badRequest("invalid_dates", "The end must be after the start.");
        }
        boolean duplicate = jdbc.sql("""
                SELECT count(*) FROM role_assignment WHERE user_account_id = ? AND role_id = ? AND revoked_at IS NULL
                  AND scope_type = ? AND scope_id = ? AND (valid_to IS NULL OR valid_to > now())""")
                .params(userId, role.id(), c.orgUnitId() == null ? "TENANT" : "ORG_UNIT", scopeId(c.orgUnitId()))
                .query(Long.class).single() > 0;
        if (duplicate) throw ApiException.conflict("role_already_assigned", "This role is already assigned at that scope.");
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id, valid_from, valid_to, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, TenantContext.requireTenantId(), userId, role.id(), c.orgUnitId() == null ? "TENANT" : "ORG_UNIT",
                        scopeId(c.orgUnitId()), Timestamp.from(from), c.validTo() == null ? null : Timestamp.from(c.validTo()),
                        CurrentUser.require().userId())
                .update();
        Map<String, String> details = roleDetails(userId, role, c.orgUnitId());
        if (c.validTo() != null) details.put("validTo", c.validTo().toString());
        audit.recordSuccess("role.assigned", "role_assignment", id.toString(), details);
        outbox.append("role_assignment", id.toString(), "role.assigned", details);
        if (user.subject() != null) principals.evictSubject(TenantContext.requireTenantId(), user.subject());
        return listRoleAssignments(userId, false).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void revokeRole(UUID userId, UUID assignmentId, String reason) {
        UserService.Row user = users.requireVisible(userId);
        requireNotSelf(userId);
        record A(UUID roleId, String scopeType, UUID scopeId) {}
        A a = jdbc.sql("SELECT role_id, scope_type, scope_id FROM role_assignment WHERE id = ? AND user_account_id = ? AND revoked_at IS NULL")
                .params(assignmentId, userId).query((rs, n) -> new A(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Role assignment"));
        Role role = role(a.roleId());
        UUID unit = "ORG_UNIT".equals(a.scopeType()) ? a.scopeId() : null;
        requireCanGrant(role, unit, "role.revocation_denied", userId);
        String why = UserService.optional(reason, 500);
        if (why == null) throw ApiException.badRequest("reason_required", "Give a reason; it is recorded in the history.");
        if ("ORG_ADMIN".equals(role.code()) && unit == null) {
            // other active tenant-level ORG_ADMIN assignment of the same user keeps the access; otherwise check the count
            long sameUserOthers = jdbc.sql("""
                    SELECT count(*) FROM role_assignment ra WHERE ra.user_account_id = ? AND ra.role_id = ? AND ra.scope_type = 'TENANT'
                      AND ra.revoked_at IS NULL AND ra.id <> ?""").params(userId, role.id(), assignmentId).query(Long.class).single();
            if (sameUserOthers == 0) RoleRules.requireNotLastOrganizationAdmin(jdbc, userId);
        }
        jdbc.sql("UPDATE role_assignment SET revoked_at = now(), revoked_by = ?, revocation_reason = ? WHERE id = ?")
                .params(CurrentUser.require().userId(), why, assignmentId).update();
        Map<String, String> details = roleDetails(userId, role, unit);
        details.put("reason", why);
        audit.recordSuccess("role.revoked", "role_assignment", assignmentId.toString(), details);
        outbox.append("role_assignment", assignmentId.toString(), "role.revoked", details);
        if (user.subject() != null) principals.evictSubject(TenantContext.requireTenantId(), user.subject());
    }

    // ------------------------------------------------------------------------------------------ access overview

    @Transactional(readOnly = true)
    public Access access(UUID userId) {
        UserService.Row user = users.requireVisible(userId);
        List<RoleAssignmentView> active = listRoleAssignments(userId, false);
        Map<String, String> descriptions = new TreeMap<>();
        for (Permission p : Permission.values()) descriptions.put(p.key(), p.displayName());
        // permission → scope key → scope
        Map<String, Map<String, PermissionScope>> byPermission = new TreeMap<>();
        for (RoleAssignmentView ra : active) {
            if (ra.validFrom() != null && ra.validFrom().isAfter(Instant.now())) continue;
            if (!"ACTIVE".equals(ra.roleStatus())) continue;                       // a deactivated role grants nothing
            List<String> perms = jdbc.sql("SELECT unnest(permissions) FROM role WHERE id = ?").param(ra.roleId()).query(String.class).list();
            for (String p : perms) {
                boolean orgOnly = Permission.fromKey(p).map(x -> x.scope() == Permission.ScopeRule.ORGANIZATION).orElse(false);
                if (orgOnly && !"TENANT".equals(ra.scopeType())) continue;          // not effective below the organization
                Map<String, PermissionScope> scopes = byPermission.computeIfAbsent(p, k -> new LinkedHashMap<>());
                String key = ra.scopeType() + ":" + ra.orgUnitId();
                PermissionScope existing = scopes.get(key);
                List<String> via = new ArrayList<>(existing == null ? List.of() : existing.viaRoles());
                if (!via.contains(ra.roleName())) via.add(ra.roleName());
                scopes.put(key, new PermissionScope(ra.scopeType(), ra.orgUnitId(), ra.scopeName(), via));
            }
        }
        List<EffectivePermission> permissions = byPermission.entrySet().stream()
                .map(e -> new EffectivePermission(e.getKey(), descriptions.getOrDefault(e.getKey(), e.getKey()), List.copyOf(e.getValue().values())))
                .toList();
        return new Access(user.summary().status(), user.summary().invitationStatus(), listOrgAssignments(Subject.USER, userId), active, permissions);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private record Role(UUID id, String code, String name, List<String> permissions, boolean system, String status,
                        UUID scopeOrgUnitId, String scopeName) {}

    private Role role(UUID roleId) {
        Role r = jdbc.sql("""
                SELECT r.id, r.code, r.name, r.permissions, r.system_role, r.status, r.scope_org_unit_id, ou.name
                FROM role r LEFT JOIN org_unit ou ON ou.id = r.scope_org_unit_id WHERE r.id = ? AND r.archived_at IS NULL""").param(roleId)
                .query((rs, n) -> new Role(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        Arrays.asList((String[]) rs.getArray(4).getArray()), rs.getBoolean(5), rs.getString(6),
                        rs.getObject(7, UUID.class), rs.getString(8)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_role", "Choose an existing role."));
        if (RoleRules.NOT_ASSIGNABLE.contains(r.code())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "role_not_assignable", "This role cannot be assigned here.");
        }
        return r;
    }

    /** role.assign at the scope, plus every permission of the role at that scope (no escalation). */
    private void requireCanGrant(Role role, UUID orgUnitId, String deniedAction, UUID userId) {
        if (orgUnitId != null) {
            boolean exists = jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ?").param(orgUnitId).query(Long.class).single() > 0;
            if (!exists) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_org_unit", "Choose a unit of your organization.");
        }
        authz.require(Permission.ROLE_ASSIGN.key(), orgUnitId);
        List<String> missing = role.permissions().stream().filter(p -> !authz.hasInOrgUnit(p, orgUnitId)).sorted().toList();
        if (!missing.isEmpty()) {
            Map<String, String> details = roleDetails(userId, role, orgUnitId);
            details.put("missingPermissions", String.join(",", missing));
            audit.recordDenied(deniedAction, details);
            throw new ApiException(HttpStatus.FORBIDDEN, "privilege_escalation",
                    "You can only manage roles whose permissions you hold yourself at this scope. Missing: " + String.join(", ", missing) + ".");
        }
    }

    private void requireNotSelf(UUID userId) {
        if (CurrentUser.require().userId().equals(userId)) {
            audit.recordDenied("role.self_assignment_denied", Map.of("userId", userId.toString()));
            throw new ApiException(HttpStatus.FORBIDDEN, "self_assignment_forbidden", "You cannot change your own roles.");
        }
    }

    private Map<String, String> roleDetails(UUID userId, Role role, UUID orgUnitId) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("userId", userId.toString());
        d.put("role", role.code());
        d.put("scope", orgUnitId == null ? "TENANT" : "ORG_UNIT:" + orgUnitId);
        return d;
    }

    private UUID scopeId(UUID orgUnitId) {
        return orgUnitId == null ? TenantContext.requireTenantId() : orgUnitId;
    }

    private List<RoleAssignmentView> listRoleAssignments(UUID userId, boolean includeInactive) {
        return jdbc.sql("""
                SELECT ra.id, r.id, r.code, r.name, ra.scope_type, ra.scope_id, ou.name AS unit_name, ra.valid_from, ra.valid_to,
                       ra.created_at, g.display_name AS granted_by, ra.revoked_at, rv.display_name AS revoked_by, ra.revocation_reason,
                       (ra.revoked_at IS NULL AND (ra.valid_to IS NULL OR ra.valid_to > now())) AS active,
                       r.status AS role_status, r.system_role
                FROM role_assignment ra JOIN role r ON r.id = ra.role_id
                LEFT JOIN org_unit ou ON ra.scope_type = 'ORG_UNIT' AND ou.id = ra.scope_id
                LEFT JOIN user_account g ON g.id = ra.created_by
                LEFT JOIN user_account rv ON rv.id = ra.revoked_by
                WHERE ra.user_account_id = ?""" + (includeInactive ? "" : " AND ra.revoked_at IS NULL AND (ra.valid_to IS NULL OR ra.valid_to > now())")
                + " ORDER BY active DESC, ra.created_at DESC")
                .param(userId)
                .query((rs, n) -> new RoleAssignmentView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), "ORG_UNIT".equals(rs.getString(5)) ? rs.getObject(6, UUID.class) : null,
                        "TENANT".equals(rs.getString(5)) ? "Whole organization" : rs.getString(7),
                        UserService.instant(rs, "valid_from"), UserService.instant(rs, "valid_to"), UserService.instant(rs, "created_at"),
                        rs.getString("granted_by"), UserService.instant(rs, "revoked_at"), rs.getString("revoked_by"),
                        rs.getString("revocation_reason"), rs.getBoolean("active"), rs.getString("role_status"), rs.getBoolean("system_role")))
                .list();
    }

    List<OrgAssignmentView> listOrgAssignments(Subject subject, UUID id) {
        String sql = """
                SELECT a.id, a.org_unit_id, ou.name, ou.unit_type, a.is_primary, a.title, a.starts_on, a.ends_on, '%s' AS source
                FROM org_assignment a LEFT JOIN org_unit ou ON ou.id = a.org_unit_id WHERE a.%s = ?""";
        List<Object> params = new ArrayList<>(List.of(id));
        String query = sql.formatted(subject == Subject.USER ? "USER" : "STAFF", column(subject));
        if (subject == Subject.USER) {
            query += " UNION ALL " + sql.formatted("STAFF", "staff_profile_id")
                    .replace("WHERE a.staff_profile_id = ?", "WHERE a.staff_profile_id = (SELECT id FROM staff_profile WHERE user_account_id = ?)");
            params.add(id);
        }
        return jdbc.sql("SELECT * FROM (" + query + ") x ORDER BY is_primary DESC, name NULLS FIRST").params(params)
                .query((rs, n) -> new OrgAssignmentView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3) == null ? "Whole organization" : rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        rs.getString(6), localDate(rs.getDate(7)), localDate(rs.getDate(8)), rs.getString(9)))
                .list();
    }

    private void requireRead(Subject subject, UUID id) {
        if (subject == Subject.USER) users.requireVisible(id);
        else if (!exists("staff_profile", id) ) throw ApiException.notFound("Staff profile");
        else if (!scope.canRead(Permission.STAFF_READ.key(), scope.staffUnits(id))) throw new AccessDeniedException("Staff outside scope");
    }

    private void requireManage(Subject subject, UUID id) {
        if (subject == Subject.USER) {
            users.requireManageable(id);
        } else {
            if (!exists("staff_profile", id)) throw ApiException.notFound("Staff profile");
            if (!scope.canManage(Permission.STAFF_MANAGE.key(), scope.staffUnits(id))) throw new AccessDeniedException("Staff outside scope");
        }
    }

    private boolean exists(String table, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = ?").param(id).query(Long.class).single() > 0;
    }

    private static String managePermission(Subject subject) {
        return subject == Subject.USER ? Permission.USER_MANAGE.key() : Permission.STAFF_MANAGE.key();
    }

    private static String column(Subject subject) {
        return subject == Subject.USER ? "user_account_id" : "staff_profile_id";
    }

    private void auditAssignment(String action, Subject subject, UUID id, UUID assignmentId, UUID orgUnitId) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put(subject == Subject.USER ? "userId" : "staffProfileId", id.toString());
        if (orgUnitId != null) d.put("orgUnitId", orgUnitId.toString());
        audit.recordSuccess(action, "org_assignment", assignmentId.toString(), d);
        outbox.append("org_assignment", assignmentId.toString(), action, d);
        if (subject == Subject.USER) {
            users.find(id).map(UserService.Row::subject).filter(java.util.Objects::nonNull)
                    .ifPresent(s -> principals.evictSubject(TenantContext.requireTenantId(), s));
        }
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static LocalDate localDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
