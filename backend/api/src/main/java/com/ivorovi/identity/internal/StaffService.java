package com.ivorovi.identity.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Staff profiles: the employment record of a person, independent of whether they have a login. A profile can be
 * linked to one user account when the person needs access.
 */
@Service
public class StaffService {

    public enum Category { FACULTY, DEPARTMENT_ADMIN, ACADEMIC_ADMIN, EVALUATOR, EXAM_CONTROLLER, PROCTOR,
        ADMISSIONS_OFFICER, PLACEMENT_OFFICER, ADMINISTRATIVE_STAFF }

    public enum EmploymentStatus { ACTIVE, ON_LEAVE, SEPARATED }

    public enum EmploymentType { PERMANENT, CONTRACT, VISITING, ADJUNCT }

    @Schema(name = "StaffProfile")
    public record StaffView(UUID id, String staffNo, String givenName, String familyName, String displayName, String email,
                            String phone, Category category, String designation, EmploymentStatus employmentStatus,
                            EmploymentType employmentType, LocalDate joinedOn, UUID userId, String userEmail,
                            UserService.AccountStatus userStatus, String primaryUnitName, List<String> units,
                            Instant createdAt, Instant updatedAt, long version) {}

    @Schema(name = "StaffDetail")
    public record StaffDetail(StaffView staff, List<AssignmentService.OrgAssignmentView> assignments, boolean canManage) {}

    public record Query(String q, Category category, EmploymentStatus status, UUID orgUnitId, Boolean linked) {}

    public record Command(String staffNo, String givenName, String familyName, String email, String phone, Category category,
                          String designation, EmploymentStatus employmentStatus, EmploymentType employmentType,
                          LocalDate joinedOn, UUID orgUnitId, Long version) {}

    private static final Pattern STAFF_NO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_.-]{0,49}$");
    private static final Map<String, String> SORTS = Map.of("name", "display_name", "staffNo", "s.staff_no",
            "category", "s.category", "status", "s.employment_status", "joinedOn", "s.joined_on");
    static final String READ = Permission.STAFF_READ.key();
    static final String MANAGE = Permission.STAFF_MANAGE.key();
    private static final String SELECT = """
            SELECT s.*, (s.given_name || coalesce(' ' || s.family_name, '')) AS display_name,
                   u.email AS user_email, u.status AS user_status,
                   (SELECT coalesce(ou.name, 'Whole organization') FROM org_assignment a LEFT JOIN org_unit ou ON ou.id = a.org_unit_id
                    WHERE a.staff_profile_id = s.id AND a.is_primary) AS primary_unit,
                   (SELECT array_agg(coalesce(ou.name, 'Whole organization') ORDER BY a.is_primary DESC, ou.name)
                    FROM org_assignment a LEFT JOIN org_unit ou ON ou.id = a.org_unit_id WHERE a.staff_profile_id = s.id) AS units
            FROM staff_profile s LEFT JOIN user_account u ON u.id = s.user_account_id""";

    private final JdbcClient jdbc;
    private final Authz authz;
    private final PeopleScope scope;
    private final AuditLog audit;
    private final Outbox outbox;
    private final AssignmentService assignments;
    private final UserService users;

    StaffService(JdbcClient jdbc, Authz authz, PeopleScope scope, AuditLog audit, Outbox outbox,
                 AssignmentService assignments, UserService users) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.scope = scope;
        this.audit = audit;
        this.outbox = outbox;
        this.assignments = assignments;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserService.Page<StaffView> list(Query q, int page, int size, String sort, boolean ascending) {
        if (!authz.hasAnywhere(READ)) throw new AccessDeniedException("Missing permission " + READ);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (q.category() != null) { where.append(" AND s.category = ?"); params.add(q.category().name()); }
        if (q.status() != null) { where.append(" AND s.employment_status = ?"); params.add(q.status().name()); }
        if (q.linked() != null) where.append(q.linked() ? " AND s.user_account_id IS NOT NULL" : " AND s.user_account_id IS NULL");
        if (q.q() != null && !q.q().isBlank()) {
            where.append(" AND (s.given_name || ' ' || coalesce(s.family_name, '') ILIKE ? OR s.staff_no ILIKE ? OR s.email ILIKE ? OR s.designation ILIKE ?)");
            String like = UserService.like(q.q());
            params.add(like); params.add(like); params.add(like); params.add(like);
        }
        if (q.orgUnitId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM org_assignment fa JOIN org_unit fu ON fu.id = fa.org_unit_id "
                    + "JOIN org_unit fr ON fr.id = ? WHERE fa.staff_profile_id = s.id AND fu.path <@ fr.path)");
            params.add(q.orgUnitId());
        }
        if (!scope.appendVisibility(READ, PeopleScope.STAFF_UNITS_OF_S, where, params)) return new UserService.Page<>(List.of(), 0);
        long total = jdbc.sql("SELECT count(*) FROM staff_profile s" + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((long) page * size);
        String order = SORTS.getOrDefault(sort, "display_name") + (ascending ? " ASC" : " DESC") + " NULLS LAST, s.id";
        return new UserService.Page<>(jdbc.sql(SELECT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?").params(params)
                .query(this::map).list(), total);
    }

    @Transactional(readOnly = true)
    public StaffDetail detail(UUID id) {
        StaffView s = find(id).orElseThrow(() -> ApiException.notFound("Staff profile"));
        List<UUID> units = scope.staffUnits(id);
        if (!scope.canRead(READ, units)) throw new AccessDeniedException("Staff outside scope");
        return new StaffDetail(s, assignments.listOrgAssignments(AssignmentService.Subject.STAFF, id), scope.canManage(MANAGE, units));
    }

    @Transactional
    public UUID create(Command c) {
        authz.require(MANAGE, c.orgUnitId());
        if (c.orgUnitId() != null) {
            boolean active = jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ? AND status = 'ACTIVE'").param(c.orgUnitId()).query(Long.class).single() > 0;
            if (!active) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_org_unit", "Choose an active unit of your organization.");
        }
        Fields f = validate(c);
        UUID id = Ids.newId();
        UUID actor = CurrentUser.require().userId();
        try {
            jdbc.sql("""
                    INSERT INTO staff_profile (id, tenant_id, staff_no, given_name, family_name, email, phone, category, designation,
                        employment_status, employment_type, joined_on, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), f.staffNo, f.given, f.family, f.email, f.phone, c.category().name(),
                            f.designation, f.status.name(), c.employmentType() == null ? null : c.employmentType().name(),
                            c.joinedOn() == null ? null : java.sql.Date.valueOf(c.joinedOn()), actor, actor)
                    .update();
        } catch (DuplicateKeyException e) {
            throw duplicateStaffNo();
        }
        jdbc.sql("INSERT INTO org_assignment (id, tenant_id, staff_profile_id, org_unit_id, is_primary, title, created_by) VALUES (?, ?, ?, ?, true, ?, ?)")
                .params(Ids.newId(), TenantContext.requireTenantId(), id, c.orgUnitId(), f.designation, actor).update();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("staffNo", f.staffNo);
        details.put("category", c.category().name());
        details.put("orgUnitId", c.orgUnitId() == null ? "organization" : c.orgUnitId().toString());
        audit.recordSuccess("staff.created", "staff_profile", id.toString(), details);
        outbox.append("staff_profile", id.toString(), "staff.created", details);
        return id;
    }

    @Transactional
    public void update(UUID id, Command c) {
        StaffView before = requireManageable(id);
        if (c.version() == null || c.version() != before.version()) {
            throw ApiException.conflict("concurrent_modification", "This profile was changed by someone else. Reload and try again.");
        }
        Fields f = validate(c);
        try {
            jdbc.sql("""
                    UPDATE staff_profile SET staff_no = ?, given_name = ?, family_name = ?, email = ?, phone = ?, category = ?,
                        designation = ?, employment_status = ?, employment_type = ?, joined_on = ?, updated_at = now(),
                        updated_by = ?, version = version + 1 WHERE id = ?""")
                    .params(f.staffNo, f.given, f.family, f.email, f.phone, c.category().name(), f.designation, f.status.name(),
                            c.employmentType() == null ? null : c.employmentType().name(),
                            c.joinedOn() == null ? null : java.sql.Date.valueOf(c.joinedOn()), CurrentUser.require().userId(), id)
                    .update();
        } catch (DuplicateKeyException e) {
            throw duplicateStaffNo();
        }
        Map<String, String> details = new LinkedHashMap<>();
        if (!before.staffNo().equals(f.staffNo)) details.put("staffNo", before.staffNo() + " → " + f.staffNo);
        if (before.category() != c.category()) details.put("category", before.category() + " → " + c.category());
        if (before.employmentStatus() != f.status) details.put("employmentStatus", before.employmentStatus() + " → " + f.status);
        audit.recordSuccess("staff.updated", "staff_profile", id.toString(), details);
        outbox.append("staff_profile", id.toString(), "staff.updated", details);
    }

    /** Links the profile to a user account; both must be manageable by the caller. */
    @Transactional
    public void link(UUID id, UUID userId) {
        StaffView s = requireManageable(id);
        users.requireManageable(userId);
        if (s.userId() != null) throw ApiException.conflict("already_linked", "This profile is already linked to a user account. Unlink it first.");
        try {
            jdbc.sql("UPDATE staff_profile SET user_account_id = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(userId, id).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("user_has_profile", "This user account is already linked to another staff profile.");
        }
        audit.recordSuccess("staff.linked", "staff_profile", id.toString(), Map.of("userId", userId.toString()));
        outbox.append("staff_profile", id.toString(), "staff.linked", Map.of("userId", userId.toString()));
    }

    @Transactional
    public void unlink(UUID id) {
        StaffView s = requireManageable(id);
        if (s.userId() == null) throw ApiException.conflict("not_linked", "This profile is not linked to a user account.");
        jdbc.sql("UPDATE staff_profile SET user_account_id = NULL, updated_at = now(), version = version + 1 WHERE id = ?").param(id).update();
        audit.recordSuccess("staff.unlinked", "staff_profile", id.toString(), Map.of("userId", s.userId().toString()));
        outbox.append("staff_profile", id.toString(), "staff.unlinked", Map.of("userId", s.userId().toString()));
    }

    private StaffView requireManageable(UUID id) {
        StaffView s = find(id).orElseThrow(() -> ApiException.notFound("Staff profile"));
        if (!scope.canManage(MANAGE, scope.staffUnits(id))) throw new AccessDeniedException("Staff outside scope");
        return s;
    }

    private Optional<StaffView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE s.id = ?").param(id).query(this::map).optional();
    }

    private record Fields(String staffNo, String given, String family, String email, String phone, String designation, EmploymentStatus status) {}

    private static Fields validate(Command c) {
        String no = c.staffNo() == null ? "" : c.staffNo().trim();
        if (!STAFF_NO.matcher(no).matches()) {
            throw ApiException.badRequest("invalid_staff_no", "Use letters, digits and / _ . - (up to 50 characters).");
        }
        if (c.category() == null) throw ApiException.badRequest("invalid_category", "Choose a staff category.");
        String email = c.email() == null || c.email().isBlank() ? null : UserService.normaliseEmail(c.email());
        String phone = UserService.optional(c.phone(), 30);
        return new Fields(no, UserService.required("givenName", c.givenName(), 100), UserService.optional(c.familyName(), 100),
                email, phone, UserService.optional(c.designation(), 100),
                c.employmentStatus() == null ? EmploymentStatus.ACTIVE : c.employmentStatus());
    }

    private static ApiException duplicateStaffNo() {
        return new ApiException(HttpStatus.CONFLICT, "staff_no_in_use", "Another staff profile already uses this staff ID.");
    }

    private StaffView map(ResultSet rs, int n) throws SQLException {
        java.sql.Array units = rs.getArray("units");
        java.sql.Date joined = rs.getDate("joined_on");
        String type = rs.getString("employment_type");
        String userStatus = rs.getString("user_status");
        return new StaffView(rs.getObject("id", UUID.class), rs.getString("staff_no"), rs.getString("given_name"),
                rs.getString("family_name"), rs.getString("display_name"), rs.getString("email"), rs.getString("phone"),
                Category.valueOf(rs.getString("category")), rs.getString("designation"),
                EmploymentStatus.valueOf(rs.getString("employment_status")), type == null ? null : EmploymentType.valueOf(type),
                joined == null ? null : joined.toLocalDate(), rs.getObject("user_account_id", UUID.class), rs.getString("user_email"),
                userStatus == null ? null : UserService.AccountStatus.valueOf(userStatus), rs.getString("primary_unit"),
                units == null ? List.of() : java.util.Arrays.stream((Object[]) units.getArray()).map(String::valueOf).toList(),
                UserService.instant(rs, "created_at"), UserService.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
