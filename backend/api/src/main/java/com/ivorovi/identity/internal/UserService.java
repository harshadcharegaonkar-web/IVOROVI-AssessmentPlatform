package com.ivorovi.identity.internal;

import com.ivorovi.identity.IdentityProvisioner;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.security.PrincipalCache;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User accounts of an organization: listing, profile, lifecycle. A new user starts as {@code INVITED} (no login
 * yet); accepting the invitation creates the identity-provider account and makes the user {@code ACTIVE}.
 * Suspension is temporary, deactivation is for people who left; both block sign-in and every API call at once.
 */
@Service
public class UserService {

    public enum AccountStatus { INVITED, ACTIVE, SUSPENDED, DEACTIVATED }

    public enum InvitationState { NONE, PENDING, EXPIRED, ACCEPTED, REVOKED }

    public enum StatusAction { SUSPEND, REACTIVATE, DEACTIVATE, ACTIVATE }

    @Schema(name = "UserSummary")
    public record UserSummary(UUID id, String email, String givenName, String familyName, String displayName, String phone,
                              AccountStatus status, InvitationState invitationStatus, Instant lastLoginAt, Instant createdAt,
                              List<String> roles, List<String> units, UUID staffProfileId, long version) {}

    @Schema(name = "UserDetail")
    public record UserDetail(UserSummary user, String statusReason, Instant statusChangedAt, boolean canManage,
                             boolean canAssignRoles, boolean self, StaffLink staffProfile,
                             InvitationService.InvitationView invitation) {}

    @Schema(name = "UserStaffLink")
    public record StaffLink(UUID id, String staffNo, String category, String designation) {}

    public record Query(String q, AccountStatus status, InvitationState invitation, UUID orgUnitId, String role) {}

    public record Page<T>(List<T> items, long total) {}

    public record CreateCommand(String email, String givenName, String familyName, String phone, UUID orgUnitId,
                                String title, boolean sendInvitation) {}

    public record UpdateCommand(long version, String email, String givenName, String familyName, String phone) {}

    static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{6,30}$");
    private static final Map<String, String> SORTS = Map.of("name", "u.display_name", "email", "u.email",
            "status", "u.status", "lastLoginAt", "u.last_login_at", "createdAt", "u.created_at");
    static final String READ = Permission.USER_READ.key();
    static final String MANAGE = Permission.USER_MANAGE.key();

    private static final String INVITATION_STATE = """
            (SELECT CASE WHEN i.status = 'PENDING' AND i.expires_at <= now() THEN 'EXPIRED' ELSE i.status END
             FROM user_invitation i WHERE i.user_account_id = u.id ORDER BY i.created_at DESC LIMIT 1)""";
    private static final String SELECT_USER =
            "SELECT u.id, u.email, u.given_name, u.family_name, u.display_name, u.phone, u.status, u.last_login_at,"
            + " u.created_at, u.version, u.status_reason, u.status_changed_at, u.external_subject, "
            + INVITATION_STATE + " AS invitation_state,"
            + " (SELECT array_agg(DISTINCT r.code ORDER BY r.code) FROM role_assignment ra JOIN role r ON r.id = ra.role_id"
            + "  WHERE ra.user_account_id = u.id AND ra.revoked_at IS NULL AND (ra.valid_to IS NULL OR ra.valid_to > now())) AS roles,"
            + " (SELECT array_agg(DISTINCT coalesce(ou.name, 'Organization') ORDER BY coalesce(ou.name, 'Organization'))"
            + "  FROM (" + PeopleScope.USER_UNITS_OF_U + ") h LEFT JOIN org_unit ou ON ou.id = h.unit) AS units,"
            + " (SELECT sp.id FROM staff_profile sp WHERE sp.user_account_id = u.id) AS staff_id"
            + " FROM user_account u";

    private final JdbcClient jdbc;
    private final PeopleScope scope;
    private final AuditLog audit;
    private final Outbox outbox;
    private final PrincipalCache principals;
    private final IdentityProvisioner idp;
    private final InvitationService invitations;
    private final com.ivorovi.platform.security.Authz authz;

    UserService(JdbcClient jdbc, PeopleScope scope, AuditLog audit, Outbox outbox, PrincipalCache principals,
                IdentityProvisioner idp, InvitationService invitations, com.ivorovi.platform.security.Authz authz) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.audit = audit;
        this.outbox = outbox;
        this.principals = principals;
        this.idp = idp;
        this.invitations = invitations;
        this.authz = authz;
    }

    // ------------------------------------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<UserSummary> list(Query q, int page, int size, String sort, boolean ascending) {
        if (!authz.hasAnywhere(READ)) throw new AccessDeniedException("Missing permission " + READ);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (q.status() != null) { where.append(" AND u.status = ?"); params.add(q.status().name()); }
        if (q.invitation() != null) {
            if (q.invitation() == InvitationState.NONE) where.append(" AND ").append(INVITATION_STATE).append(" IS NULL");
            else { where.append(" AND ").append(INVITATION_STATE).append(" = ?"); params.add(q.invitation().name()); }
        }
        if (q.q() != null && !q.q().isBlank()) {
            where.append(" AND (u.display_name ILIKE ? OR u.email ILIKE ?)");
            String like = like(q.q());
            params.add(like); params.add(like);
        }
        if (q.orgUnitId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM (").append(PeopleScope.USER_UNITS_OF_U)
                 .append(") f JOIN org_unit fu ON fu.id = f.unit JOIN org_unit fr ON fr.id = ? WHERE fu.path <@ fr.path)");
            params.add(q.orgUnitId());
        }
        if (q.role() != null && !q.role().isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM role_assignment ra JOIN role r ON r.id = ra.role_id "
                    + "WHERE ra.user_account_id = u.id AND ra.revoked_at IS NULL AND r.code = ?)");
            params.add(q.role());
        }
        if (!scope.appendVisibility(READ, PeopleScope.USER_UNITS_OF_U, where, params)) return new Page<>(List.of(), 0);
        long total = jdbc.sql("SELECT count(*) FROM user_account u" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        String order = SORTS.getOrDefault(sort, "u.display_name") + (ascending ? " ASC" : " DESC") + " NULLS LAST, u.id";
        List<UserSummary> items = jdbc.sql(SELECT_USER + where + " ORDER BY " + order + " LIMIT ? OFFSET ?")
                .params(pageParams).query(this::map).list();
        return new Page<>(items, total);
    }

    @Transactional(readOnly = true)
    public UserDetail detail(UUID id) {
        Row row = requireVisible(id);
        List<UUID> units = scope.userUnits(id);
        boolean self = CurrentUser.get().map(p -> p.userId().equals(id)).orElse(false);
        StaffLink staff = jdbc.sql("SELECT id, staff_no, category, designation FROM staff_profile WHERE user_account_id = ?")
                .param(id).query((rs, n) -> new StaffLink(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional().orElse(null);
        return new UserDetail(row.summary(), row.statusReason(), row.statusChangedAt(),
                !self && scope.canManage(MANAGE, units), authz.hasAnywhere(Permission.ROLE_ASSIGN.key()) && !self, self,
                staff, invitations.latestFor(id).orElse(null));
    }

    // ---------------------------------------------------------------------------------------------- mutations

    @Transactional
    public CreateResult create(CreateCommand c) {
        authz.require(MANAGE, c.orgUnitId());
        String email = normaliseEmail(c.email());
        String given = required("givenName", c.givenName(), 100);
        String family = optional(c.familyName(), 100);
        String phone = phone(c.phone());
        if (c.orgUnitId() != null) requireUnit(c.orgUnitId());
        UUID id = Ids.newId();
        UUID actor = CurrentUser.require().userId();
        try {
            jdbc.sql("""
                    INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name, family_name,
                                              phone, status, created_by, updated_by)
                    VALUES (?, ?, NULL, ?, ?, ?, ?, ?, 'INVITED', ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), email, displayName(given, family), given, family, phone, actor, actor)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "email_in_use", "A user with this email address already exists in your organization.");
        }
        jdbc.sql("""
                INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary, title, created_by)
                VALUES (?, ?, ?, ?, true, ?, ?)""")
                .params(Ids.newId(), TenantContext.requireTenantId(), id, c.orgUnitId(), optional(c.title(), 100), actor).update();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("email", email);
        details.put("orgUnitId", c.orgUnitId() == null ? "organization" : c.orgUnitId().toString());
        audit.recordSuccess("user.created", "user_account", id.toString(), details);
        outbox.append("user_account", id.toString(), "user.created", details);
        Optional<InvitationService.Issued> issued = c.sendInvitation() ? Optional.of(invitations.issue(id)) : Optional.empty();
        return new CreateResult(id, issued);
    }

    public record CreateResult(UUID id, Optional<InvitationService.Issued> invitation) {}

    @Transactional
    public void update(UUID id, UpdateCommand c) {
        Row row = requireManageable(id);
        checkVersion(row, c.version());
        String given = c.givenName() == null ? row.summary().givenName() : required("givenName", c.givenName(), 100);
        String family = c.familyName() == null ? row.summary().familyName() : optional(c.familyName(), 100);
        String phone = c.phone() == null ? row.summary().phone() : phone(c.phone());
        String email = row.summary().email();
        if (c.email() != null && !normaliseEmail(c.email()).equals(email)) {
            if (row.summary().status() != AccountStatus.INVITED) {
                throw new ApiException(HttpStatus.CONFLICT, "email_locked",
                        "The email address of an account that has signed in is managed by the identity provider and cannot be changed here.");
            }
            email = normaliseEmail(c.email());
        }
        try {
            jdbc.sql("""
                    UPDATE user_account SET email = ?, given_name = ?, family_name = ?, display_name = ?, phone = ?,
                           updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""")
                    .params(email, given, family, displayName(given, family), phone, CurrentUser.require().userId(), id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "email_in_use", "A user with this email address already exists in your organization.");
        }
        if (!email.equals(row.summary().email())) invitations.revokeOpenFor(id, "Email address changed");
        Map<String, String> details = new LinkedHashMap<>();
        diff(details, "email", row.summary().email(), email);
        diff(details, "givenName", row.summary().givenName(), given);
        diff(details, "familyName", row.summary().familyName(), family);
        diff(details, "phone", row.summary().phone() == null ? null : "set", phone == null ? null : "set");
        audit.recordSuccess("user.updated", "user_account", id.toString(), details);
        outbox.append("user_account", id.toString(), "user.updated", details);
        if (row.subject() != null) principals.evictSubject(TenantContext.requireTenantId(), row.subject());
    }

    /**
     * Status transitions. The identity provider is updated before the database commits: if it fails, nothing
     * changes (502). If the commit failed after a successful disable, the account is disabled in both places
     * except Ivorovi still says ACTIVE; Ivorovi's own check on every request is what gates API access.
     */
    @Transactional
    public void changeStatus(UUID id, StatusAction action, String reason, long version) {
        Row row = requireManageable(id);
        checkVersion(row, version);
        AccountStatus from = row.summary().status();
        AccountStatus to = switch (action) {
            case SUSPEND -> expect(from, AccountStatus.SUSPENDED, AccountStatus.ACTIVE);
            case REACTIVATE -> expect(from, AccountStatus.ACTIVE, AccountStatus.SUSPENDED);
            case DEACTIVATE -> expect(from, AccountStatus.DEACTIVATED, AccountStatus.ACTIVE, AccountStatus.SUSPENDED, AccountStatus.INVITED);
            case ACTIVATE -> {
                expect(from, AccountStatus.ACTIVE, AccountStatus.DEACTIVATED);
                yield row.subject() == null ? AccountStatus.INVITED : AccountStatus.ACTIVE;
            }
        };
        String why = optional(reason, 500);
        if ((action == StatusAction.SUSPEND || action == StatusAction.DEACTIVATE) && why == null) {
            throw ApiException.badRequest("reason_required", "Give a reason; it is recorded in the account history.");
        }
        if (to != AccountStatus.ACTIVE && from == AccountStatus.ACTIVE) RoleRules.requireNotLastOrganizationAdmin(jdbc, id);
        jdbc.sql("""
                UPDATE user_account SET status = ?, status_reason = ?, status_changed_at = now(), status_changed_by = ?,
                       updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""")
                .params(to.name(), why, CurrentUser.require().userId(), CurrentUser.require().userId(), id).update();
        if (to == AccountStatus.DEACTIVATED) invitations.revokeOpenFor(id, "Account deactivated");
        Map<String, String> details = new LinkedHashMap<>();
        details.put("from", from.name());
        details.put("to", to.name());
        if (why != null) details.put("reason", why);
        String event = switch (action) {
            case SUSPEND -> "user.suspended";
            case REACTIVATE -> "user.reactivated";
            case DEACTIVATE -> "user.deactivated";
            case ACTIVATE -> "user.activated";
        };
        audit.recordSuccess(event, "user_account", id.toString(), details);
        outbox.append("user_account", id.toString(), event, details);
        if (row.subject() != null) {
            principals.evictSubject(TenantContext.requireTenantId(), row.subject());
            boolean enabled = to == AccountStatus.ACTIVE;
            if (enabled != (from == AccountStatus.ACTIVE)) setIdpEnabled(row.subject(), enabled);
        }
    }

    /** Records the sign-in time reported by the identity provider (token {@code auth_time}). */
    @Transactional
    public void recordSignIn(UUID userId, Instant authTime) {
        if (authTime == null) return;
        jdbc.sql("UPDATE user_account SET last_login_at = ? WHERE id = ? AND (last_login_at IS NULL OR last_login_at < ?)")
                .params(Timestamp.from(authTime), userId, Timestamp.from(authTime)).update();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    record Row(UserSummary summary, String subject, String statusReason, Instant statusChangedAt) {}

    Row requireVisible(UUID id) {
        Row row = find(id).orElseThrow(() -> ApiException.notFound("User"));
        if (!scope.canRead(READ, scope.userUnits(id))) throw new AccessDeniedException("User outside scope");
        return row;
    }

    Row requireManageable(UUID id) {
        Row row = find(id).orElseThrow(() -> ApiException.notFound("User"));
        if (!scope.canManage(MANAGE, scope.userUnits(id))) throw new AccessDeniedException("User outside scope");
        if (CurrentUser.require().userId().equals(id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "self_modification_forbidden",
                    "You cannot change your own account here. Ask another administrator.");
        }
        return row;
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT_USER + " WHERE u.id = ?").param(id)
                .query((rs, n) -> new Row(map(rs, n), rs.getString("external_subject"), rs.getString("status_reason"),
                        instant(rs, "status_changed_at"))).optional();
    }

    private void setIdpEnabled(String subject, boolean enabled) {
        try {
            idp.setEnabled(subject, enabled);
        } catch (IdentityProvisioner.UnavailableException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "identity_provider_unavailable",
                    "The sign-in service could not be updated. Nothing was changed; try again shortly.");
        }
    }

    private UserSummary map(ResultSet rs, int n) throws SQLException {
        String inv = rs.getString("invitation_state");
        return new UserSummary(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("given_name"),
                rs.getString("family_name"), rs.getString("display_name"), rs.getString("phone"),
                AccountStatus.valueOf(rs.getString("status")), inv == null ? InvitationState.NONE : InvitationState.valueOf(inv),
                instant(rs, "last_login_at"), instant(rs, "created_at"), strings(rs, "roles"), strings(rs, "units"),
                rs.getObject("staff_id", UUID.class), rs.getLong("version"));
    }

    private static List<String> strings(ResultSet rs, String col) throws SQLException {
        java.sql.Array a = rs.getArray(col);
        return a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private void requireUnit(UUID orgUnitId) {
        boolean exists = jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ? AND status = 'ACTIVE'").param(orgUnitId)
                .query(Long.class).single() > 0;
        if (!exists) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_org_unit", "Choose an active unit of your organization.");
    }

    private static AccountStatus expect(AccountStatus from, AccountStatus to, AccountStatus... allowed) {
        if (Arrays.asList(allowed).contains(from)) return to;
        throw ApiException.conflict("invalid_status_transition", "This action is not possible for an account that is " + from.name().toLowerCase(Locale.ROOT) + ".");
    }

    private static void checkVersion(Row row, long version) {
        if (row.summary().version() != version) {
            throw ApiException.conflict("concurrent_modification", "This user was changed by someone else. Reload and try again.");
        }
    }

    public static String normaliseEmail(String raw) {
        String e = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (e.length() > 320 || !EMAIL.matcher(e).matches()) {
            throw ApiException.badRequest("invalid_email", "Enter a valid email address.");
        }
        return e;
    }

    private static String phone(String raw) {
        String p = optional(raw, 30);
        if (p != null && !PHONE.matcher(p).matches()) throw ApiException.badRequest("invalid_phone", "Enter a valid phone number.");
        return p;
    }

    static String required(String field, String raw, int max) {
        String v = optional(raw, max);
        if (v == null) throw ApiException.badRequest("invalid_" + field, "This field is required.");
        return v;
    }

    static String optional(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.length() > max) throw ApiException.badRequest("too_long", "Use at most " + max + " characters.");
        return v;
    }

    static String displayName(String given, String family) {
        return family == null ? given : given + " " + family;
    }

    static String like(String q) {
        return "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static void diff(Map<String, String> details, String key, String before, String after) {
        if (!java.util.Objects.equals(before, after)) details.put(key, String.valueOf(before) + " → " + String.valueOf(after));
    }
}
