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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles: the immutable system roles and an organization's custom roles.
 *
 * <p>Rules (all enforced here, whatever the client sends):
 * <ul>
 *   <li>System roles cannot be edited, deactivated or archived through the API.</li>
 *   <li>A custom role is defined for the whole organization or for one unit's subtree ({@code scopeOrgUnitId});
 *       managing it needs {@code role.manage} at that scope.</li>
 *   <li><b>Ceiling:</b> every permission put into a role (on creation, cloning or editing) must be held by the
 *       editor at the role's scope. Only catalogued, available, non-platform permissions are accepted.</li>
 *   <li>Organization-only permissions (e.g. settings, audit) can only be part of organization-wide roles.</li>
 *   <li>Deactivating a role suspends every assignment of it immediately (the role grants nothing until it is
 *       reactivated); archiving is possible only when no active assignment remains.</li>
 *   <li>Every change invalidates the cached principals of the whole organization after commit.</li>
 * </ul>
 */
@Service
public class RoleService {

    public enum Status { ACTIVE, INACTIVE }

    @Schema(name = "PermissionInfo")
    public record PermissionView(String code, String name, String description, Permission.Domain domain, String domainLabel,
                                 Permission.Level level, Permission.ScopeRule scope, Permission.Availability availability,
                                 boolean heldByYou) {}

    @Schema(name = "PermissionDomain")
    public record DomainView(Permission.Domain domain, String label, List<PermissionView> permissions) {}

    @Schema(name = "RoleSummary")
    public record RoleSummary(UUID id, String code, String name, String description, boolean system, Status status,
                              UUID scopeOrgUnitId, String scopeName, int permissionCount, long activeAssignments,
                              boolean organizationOnly, Instant updatedAt, long version) {}

    @Schema(name = "RoleDetail")
    public record RoleDetail(RoleSummary role, List<String> permissions, String clonedFromName, String statusReason,
                             Map<String, Long> assignmentsByScope, boolean canEdit, List<String> beyondYourCeiling) {}

    @Schema(name = "RoleHolder")
    public record Holder(UUID assignmentId, UUID userId, String userName, String email, String userStatus,
                         String scopeType, UUID orgUnitId, String scopeName, Instant validFrom, Instant validTo, boolean active) {}

    public record Command(String code, String name, String description, UUID scopeOrgUnitId, List<String> permissions,
                          UUID clonedFromRoleId, Long version) {}

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,62}$");
    private static final Map<String, String> SORTS = Map.of("name", "r.name", "code", "r.code", "status", "r.status",
            "updatedAt", "r.updated_at", "permissions", "cardinality(r.permissions)");
    private static final String MANAGE = Permission.ROLE_MANAGE.key();
    private static final String READ = Permission.ROLE_READ.key();
    private static final String SELECT = """
            SELECT r.id, r.code, r.name, r.description, r.system_role, r.status, r.scope_org_unit_id, ou.name AS scope_name,
                   cardinality(r.permissions) AS permission_count, r.permissions, r.updated_at, r.version, r.status_reason,
                   (SELECT c.name FROM role c WHERE c.id = r.cloned_from) AS cloned_from_name,
                   (SELECT count(*) FROM role_assignment ra WHERE ra.role_id = r.id AND ra.revoked_at IS NULL
                      AND (ra.valid_to IS NULL OR ra.valid_to > now())) AS active_assignments
            FROM role r LEFT JOIN org_unit ou ON ou.id = r.scope_org_unit_id""";

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final PrincipalCache principals;
    private final PeopleScope people;

    RoleService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, PrincipalCache principals, PeopleScope people) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.principals = principals;
        this.people = people;
    }

    // ------------------------------------------------------------------------------------------- catalogue

    public List<DomainView> catalogue() {
        requireRead();
        Map<Permission.Domain, List<PermissionView>> byDomain = new LinkedHashMap<>();
        for (Permission.Domain d : Permission.Domain.values()) if (d != Permission.Domain.PLATFORM) byDomain.put(d, new ArrayList<>());
        for (Permission p : Permission.values()) {
            if (p.availability() == Permission.Availability.PLATFORM) continue;
            byDomain.get(p.domain()).add(new PermissionView(p.key(), p.displayName(), p.description(), p.domain(), p.domain().label(),
                    p.level(), p.scope(), p.availability(), p.grantable() && authz.hasAnywhere(p.key())));
        }
        return byDomain.entrySet().stream().map(e -> new DomainView(e.getKey(), e.getKey().label(), e.getValue())).toList();
    }

    // ------------------------------------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public UserService.Page<RoleSummary> list(String q, Boolean system, Status status, int page, int size, String sort, boolean ascending) {
        requireRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE r.archived_at IS NULL AND NOT (r.code = ANY(?))");
        params.add(RoleRules.NOT_ASSIGNABLE.toArray(String[]::new));
        if (system != null) where.append(system ? " AND r.system_role" : " AND NOT r.system_role");
        if (status != null) { where.append(" AND r.status = ?"); params.add(status.name()); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (r.name ILIKE ? OR r.code ILIKE ? OR r.description ILIKE ?)");
            String like = UserService.like(q);
            params.add(like); params.add(like); params.add(like);
        }
        long total = jdbc.sql("SELECT count(*) FROM role r" + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((long) page * size);
        String order = SORTS.getOrDefault(sort, "r.name") + (ascending ? " ASC" : " DESC") + ", r.id";
        return new UserService.Page<>(jdbc.sql(SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?").params(params)
                .query(this::summary).list(), total);
    }

    @Transactional(readOnly = true)
    public RoleDetail detail(UUID id) {
        requireRead();
        Row r = load(id);
        Map<String, Long> byScope = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT CASE WHEN ra.scope_type = 'TENANT' THEN 'Whole organization' ELSE ou.unit_type END, count(*)
                FROM role_assignment ra LEFT JOIN org_unit ou ON ra.scope_type = 'ORG_UNIT' AND ou.id = ra.scope_id
                WHERE ra.role_id = ? AND ra.revoked_at IS NULL AND (ra.valid_to IS NULL OR ra.valid_to > now())
                GROUP BY 1 ORDER BY 1""").param(id)
                .query(rs -> { byScope.put(rs.getString(1), rs.getLong(2)); });
        boolean canEdit = !r.summary.system() && authz.hasInOrgUnit(MANAGE, r.summary.scopeOrgUnitId());
        List<String> beyond = r.permissions.stream().filter(p -> !authz.hasInOrgUnit(p, r.summary.scopeOrgUnitId())).sorted().toList();
        return new RoleDetail(r.summary, r.permissions, r.clonedFromName, r.statusReason, byScope, canEdit, beyond);
    }

    /** Who holds the role: assignments of users the caller can see. */
    @Transactional(readOnly = true)
    public UserService.Page<Holder> holders(UUID roleId, boolean includeInactive, int page, int size) {
        requireRead();
        load(roleId);
        List<Object> params = new ArrayList<>(List.of(roleId));
        StringBuilder where = new StringBuilder(" WHERE ra.role_id = ?");
        if (!includeInactive) where.append(" AND ra.revoked_at IS NULL AND (ra.valid_to IS NULL OR ra.valid_to > now())");
        if (!people.appendVisibility(UserService.READ, PeopleScope.USER_UNITS_OF_U, where, params)) return new UserService.Page<>(List.of(), 0);
        String from = " FROM role_assignment ra JOIN user_account u ON u.id = ra.user_account_id"
                + " LEFT JOIN org_unit ou ON ra.scope_type = 'ORG_UNIT' AND ou.id = ra.scope_id";
        long total = jdbc.sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((long) page * size);
        List<Holder> items = jdbc.sql("""
                SELECT ra.id, u.id, u.display_name, u.email, u.status, ra.scope_type, ou.id, ou.name, ra.valid_from, ra.valid_to,
                       (ra.revoked_at IS NULL AND (ra.valid_to IS NULL OR ra.valid_to > now())) AS active""" + from + where
                        + " ORDER BY active DESC, u.display_name, ra.id LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, n) -> new Holder(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                        "TENANT".equals(rs.getString(6)) ? "Whole organization" : rs.getString(8),
                        UserService.instant(rs, "valid_from"), UserService.instant(rs, "valid_to"), rs.getBoolean("active")))
                .list();
        return new UserService.Page<>(items, total);
    }

    // ------------------------------------------------------------------------------------------- mutations

    @Transactional
    public UUID create(Command c) {
        UUID scope = c.scopeOrgUnitId();
        requireManage(scope, "role.change_denied", Map.of("operation", "create"));
        if (scope != null) {
            boolean active = jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ? AND status = 'ACTIVE'").param(scope).query(Long.class).single() > 0;
            if (!active) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_org_unit", "Choose an active unit of your organization.");
        }
        String name = UserService.required("name", c.name(), 100);
        String code = code(c.code(), name);
        boolean reserved = code.startsWith("PLATFORM") || jdbc.sql("SELECT count(*) FROM role WHERE system_role AND code = ?").param(code)
                .query(Long.class).single() > 0;
        if (reserved) {
            audit.recordDenied("role.change_denied", Map.of("operation", "create", "reservedCode", code));
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "reserved_role_code",
                    "This code belongs to a system role. Custom roles cannot become system, platform or student roles.");
        }
        List<String> perms = validatePermissions(c.permissions(), scope, Set.of(), "create");
        Row source = null;
        if (c.clonedFromRoleId() != null) source = load(c.clonedFromRoleId());
        UUID id = Ids.newId();
        UUID actor = CurrentUser.require().userId();
        try {
            jdbc.sql("""
                    INSERT INTO role (id, tenant_id, code, name, description, system_role, permissions, scope_org_unit_id, cloned_from,
                                      created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, false, ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), code, name, UserService.optional(c.description(), 1000),
                            perms.toArray(String[]::new), scope, source == null ? null : source.summary.id(), actor, actor)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("role_code_in_use", "Another role already uses the code " + code + ". Choose a different name or code.");
        }
        Map<String, String> d = new LinkedHashMap<>();
        d.put("code", code);
        d.put("name", name);
        d.put("scope", scope == null ? "ORGANIZATION" : "ORG_UNIT:" + scope);
        d.put("permissions", String.join(",", perms));
        if (source != null) d.put("clonedFrom", source.summary.code());
        String action = source == null ? "role.created" : "role.cloned";
        audit.recordSuccess(action, "role", id.toString(), d);
        outbox.append("role", id.toString(), action, d);
        return id;
    }

    @Transactional
    public void update(UUID id, Command c) {
        lock(id);
        Row r = load(id);
        requireCustom(r, "update");
        requireManage(r.summary.scopeOrgUnitId(), "role.change_denied", Map.of("operation", "update", "role", r.summary.code()));
        if (c.version() == null || c.version() != r.summary.version()) {
            throw ApiException.conflict("concurrent_modification", "This role was changed by someone else. Reload and try again.");
        }
        String name = c.name() == null ? r.summary.name() : UserService.required("name", c.name(), 100);
        String description = c.description() == null ? r.summary.description() : UserService.optional(c.description(), 1000);
        List<String> perms = c.permissions() == null ? r.permissions
                : validatePermissions(c.permissions(), r.summary.scopeOrgUnitId(), Set.copyOf(r.permissions), "update");
        if (c.scopeOrgUnitId() != null && !c.scopeOrgUnitId().equals(r.summary.scopeOrgUnitId())) {
            throw ApiException.badRequest("scope_immutable", "The scope of a role cannot be changed. Clone it with the new scope instead.");
        }
        // assignments made at the organization level stay valid only while the role has no organization-only conflict:
        // a role assigned below the organization must not gain organization-only permissions
        boolean gainsOrgOnly = perms.stream().anyMatch(p -> Permission.fromKey(p).orElseThrow().scope() == Permission.ScopeRule.ORGANIZATION)
                && !r.summary.organizationOnly();
        if (gainsOrgOnly) {
            long unitAssignments = jdbc.sql("SELECT count(*) FROM role_assignment WHERE role_id = ? AND scope_type = 'ORG_UNIT' AND revoked_at IS NULL")
                    .param(id).query(Long.class).single();
            if (unitAssignments > 0) {
                throw ApiException.conflict("organization_only_permission_conflict",
                        "This role is assigned within units. Organization-only permissions cannot be added; create a separate organization-wide role.");
            }
        }
        // a role in use cannot gain a privileged permission: that would elevate its holders without approval (P2-08)
        if (perms.stream().anyMatch(p -> Permission.PRIVILEGED.contains(p) && !r.permissions.contains(p))) {
            long active = jdbc.sql("SELECT count(*) FROM role_assignment WHERE role_id = ? AND revoked_at IS NULL AND (valid_to IS NULL OR valid_to > now())")
                    .param(id).query(Long.class).single();
            if (active > 0) {
                audit.recordDenied("role.change_denied", Map.of("operation", "update", "role", r.summary.code(), "reason", "privileged_gain_in_use"));
                throw ApiException.conflict("privileged_role_in_use",
                        "This role is assigned. Approval or answer-key permissions cannot be added to it; create a new role and assign it through approval.");
            }
        }
        int n = jdbc.sql("""
                UPDATE role SET name = ?, description = ?, permissions = ?, updated_at = now(), updated_by = ?, version = version + 1
                WHERE id = ? AND version = ?""")
                .params(name, description, perms.toArray(String[]::new), CurrentUser.require().userId(), id, r.summary.version()).update();
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This role was changed by someone else. Reload and try again.");
        Set<String> added = new TreeSet<>(perms);
        added.removeAll(r.permissions);
        Set<String> removed = new TreeSet<>(r.permissions);
        removed.removeAll(perms);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("code", r.summary.code());
        d.put("scope", r.summary.scopeOrgUnitId() == null ? "ORGANIZATION" : "ORG_UNIT:" + r.summary.scopeOrgUnitId());
        if (!name.equals(r.summary.name())) d.put("name", r.summary.name() + " → " + name);
        if (!added.isEmpty()) d.put("permissionsAdded", String.join(",", added));
        if (!removed.isEmpty()) d.put("permissionsRemoved", String.join(",", removed));
        d.put("activeAssignments", String.valueOf(r.summary.activeAssignments()));
        audit.recordSuccess("role.updated", "role", id.toString(), d);
        outbox.append("role", id.toString(), "role.updated", d);
        principals.evictTenant(TenantContext.requireTenantId());
    }

    /**
     * Deactivation suspends every assignment of the role at once (the principal resolver ignores inactive roles and
     * every cached principal of the organization is invalidated). Assignments are kept, so reactivation restores them.
     */
    @Transactional
    public void setStatus(UUID id, Status status, String reason, long version) {
        lock(id);
        Row r = load(id);
        requireCustom(r, status == Status.ACTIVE ? "activate" : "deactivate");
        requireManage(r.summary.scopeOrgUnitId(), "role.change_denied", Map.of("operation", status.name(), "role", r.summary.code()));
        if (r.summary.version() != version) throw ApiException.conflict("concurrent_modification", "This role was changed by someone else. Reload and try again.");
        if (r.summary.status() == status) throw ApiException.conflict("invalid_status_transition", "The role is already " + status.name().toLowerCase(Locale.ROOT) + ".");
        String why = UserService.optional(reason, 500);
        if (status == Status.INACTIVE && why == null) throw ApiException.badRequest("reason_required", "Give a reason; it is recorded in the audit log.");
        if (status == Status.ACTIVE) {
            // reactivation must not restore permissions the actor could not grant today
            List<String> beyond = r.permissions.stream().filter(p -> !authz.hasInOrgUnit(p, r.summary.scopeOrgUnitId())).toList();
            if (!beyond.isEmpty()) throw escalation(r, beyond, "activate");
        }
        jdbc.sql("UPDATE role SET status = ?, status_reason = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?")
                .params(status.name(), why, CurrentUser.require().userId(), id).update();
        Map<String, String> d = new LinkedHashMap<>();
        d.put("code", r.summary.code());
        d.put("affectedAssignments", String.valueOf(r.summary.activeAssignments()));
        if (why != null) d.put("reason", why);
        String action = status == Status.ACTIVE ? "role.activated" : "role.deactivated";
        audit.recordSuccess(action, "role", id.toString(), d);
        outbox.append("role", id.toString(), action, d);
        principals.evictTenant(TenantContext.requireTenantId());
    }

    /** Archive (the API's "delete"): only for custom roles without active assignments; history is kept. */
    @Transactional
    public void archive(UUID id, long version) {
        lock(id);
        Row r = load(id);
        requireCustom(r, "archive");
        requireManage(r.summary.scopeOrgUnitId(), "role.change_denied", Map.of("operation", "archive", "role", r.summary.code()));
        if (r.summary.version() != version) throw ApiException.conflict("concurrent_modification", "This role was changed by someone else. Reload and try again.");
        long active = jdbc.sql("SELECT count(*) FROM role_assignment WHERE role_id = ? AND revoked_at IS NULL AND (valid_to IS NULL OR valid_to > now())")
                .param(id).query(Long.class).single();
        if (active > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "role_in_use",
                    "This role is assigned " + active + " time(s). Revoke or reassign those assignments, or deactivate the role instead.");
        }
        jdbc.sql("UPDATE role SET archived_at = now(), status = 'INACTIVE', updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?")
                .params(CurrentUser.require().userId(), id).update();
        audit.recordSuccess("role.archived", "role", id.toString(), Map.of("code", r.summary.code()));
        outbox.append("role", id.toString(), "role.archived", Map.of("code", r.summary.code()));
        principals.evictTenant(TenantContext.requireTenantId());
    }

    // ------------------------------------------------------------------------------------------- rules

    /** Known, grantable, within scope rules, and within the actor's ceiling (only newly added ones for edits). */
    private List<String> validatePermissions(List<String> requested, UUID scope, Set<String> alreadyInRole, String operation) {
        if (requested == null || requested.isEmpty()) throw ApiException.badRequest("permissions_required", "Choose at least one permission.");
        Set<String> unique = new LinkedHashSet<>(requested);
        List<String> unknown = new ArrayList<>();
        List<String> notGrantable = new ArrayList<>();
        for (String code : unique) {
            Optional<Permission> p = Permission.fromKey(code);
            if (p.isEmpty()) unknown.add(code);
            else if (!p.get().grantable()) notGrantable.add(code);
        }
        if (!unknown.isEmpty()) {
            audit.recordDenied("role.change_denied", Map.of("operation", operation, "unknownPermissions", String.join(",", unknown)));
            throw ApiException.badRequest("unknown_permission", "Unknown permission code(s): " + String.join(", ", unknown) + ".");
        }
        if (!notGrantable.isEmpty()) {
            audit.recordDenied("role.change_denied", Map.of("operation", operation, "notGrantable", String.join(",", notGrantable)));
            throw ApiException.badRequest("permission_not_grantable",
                    "These permissions cannot be granted to organization roles: " + String.join(", ", notGrantable) + ".");
        }
        if (scope != null) {
            List<String> orgOnly = unique.stream().filter(c -> Permission.fromKey(c).orElseThrow().scope() == Permission.ScopeRule.ORGANIZATION).toList();
            if (!orgOnly.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "organization_only_permission",
                        "These permissions apply only to the whole organization and cannot be part of a role limited to a unit: "
                                + String.join(", ", orgOnly) + ".");
            }
        }
        List<String> beyond = unique.stream().filter(c -> !alreadyInRole.contains(c)).filter(c -> !authz.hasInOrgUnit(c, scope)).sorted().toList();
        if (!beyond.isEmpty()) {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("operation", operation);
            d.put("scope", scope == null ? "ORGANIZATION" : "ORG_UNIT:" + scope);
            d.put("missingPermissions", String.join(",", beyond));
            audit.recordDenied("role.change_denied", d);
            throw new ApiException(HttpStatus.FORBIDDEN, "privilege_escalation",
                    "You can only include permissions you hold yourself at this scope. Not held: " + String.join(", ", beyond) + ".");
        }
        // stable order: catalogue order
        return Arrays.stream(Permission.values()).map(Permission::key).filter(unique::contains).toList();
    }

    private ApiException escalation(Row r, List<String> beyond, String operation) {
        audit.recordDenied("role.change_denied", Map.of("operation", operation, "role", r.summary.code(), "missingPermissions", String.join(",", beyond)));
        return new ApiException(HttpStatus.FORBIDDEN, "privilege_escalation",
                "You can only manage roles whose permissions you hold yourself at this scope. Not held: " + String.join(", ", beyond) + ".");
    }

    private void requireCustom(Row r, String operation) {
        if (r.summary.system()) {
            audit.recordDenied("role.change_denied", Map.of("operation", operation, "role", r.summary.code(), "reason", "system_role"));
            throw new ApiException(HttpStatus.FORBIDDEN, "system_role_protected",
                    "System roles are defined by Ivorovi and cannot be changed. Clone it to create an editable custom role.");
        }
    }

    private void requireManage(UUID scope, String deniedAction, Map<String, String> details) {
        if (!authz.hasInOrgUnit(MANAGE, scope)) {
            Map<String, String> d = new LinkedHashMap<>(details);
            d.put("scope", scope == null ? "ORGANIZATION" : "ORG_UNIT:" + scope);
            audit.recordDenied(deniedAction, d);
            throw new AccessDeniedException("Missing permission role.manage at scope");
        }
    }

    private void requireRead() {
        if (!authz.hasAnywhere(READ)) throw new AccessDeniedException("Missing permission role.read");
    }

    private static String code(String requested, String name) {
        String c = requested != null && !requested.isBlank() ? requested.trim().toUpperCase(Locale.ROOT)
                : "CUSTOM_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (c.length() > 63) c = c.substring(0, 63);
        if (!CODE.matcher(c).matches()) {
            throw ApiException.badRequest("invalid_role_code", "Use capital letters, digits and underscores, starting with a letter.");
        }
        return c;
    }

    /** Row lock on a custom role (no-op for system roles, which are never modified). */
    private void lock(UUID id) {
        jdbc.sql("SELECT id FROM role WHERE id = ? AND NOT system_role FOR UPDATE").param(id).query(UUID.class).list();
    }

    private record Row(RoleSummary summary, List<String> permissions, String clonedFromName, String statusReason) {}

    private Row load(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = ? AND r.archived_at IS NULL AND NOT (r.code = ANY(?))")
                .params(id, RoleRules.NOT_ASSIGNABLE.toArray(String[]::new))
                .query((rs, n) -> new Row(summary(rs, n), Arrays.asList((String[]) rs.getArray("permissions").getArray()),
                        rs.getString("cloned_from_name"), rs.getString("status_reason")))
                .optional().orElseThrow(() -> ApiException.notFound("Role"));
    }

    private RoleSummary summary(ResultSet rs, int n) throws SQLException {
        String[] perms = (String[]) rs.getArray("permissions").getArray();
        boolean orgOnly = Arrays.stream(perms).map(Permission::fromKey).flatMap(Optional::stream)
                .anyMatch(p -> p.scope() == Permission.ScopeRule.ORGANIZATION);
        return new RoleSummary(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("system_role"), Status.valueOf(rs.getString("status")), rs.getObject("scope_org_unit_id", UUID.class),
                rs.getObject("scope_org_unit_id") == null ? "Whole organization" : rs.getString("scope_name"),
                rs.getInt("permission_count"), rs.getLong("active_assignments"), orgOnly, UserService.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
