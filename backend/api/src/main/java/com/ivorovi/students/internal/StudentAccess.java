package com.ivorovi.students.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.cache.ValkeyJsonCache;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Shared rules of the students module.
 * <ul>
 *   <li>Every student has a <b>scope unit</b>: the department of the current enrolment, else of the admission, else the
 *       institution. Permissions are evaluated there (inherited from ancestors), and lists show students whose scope
 *       unit lies inside the reader's units (visibility goes down the tree, never up or sideways).</li>
 *   <li>Personal details (date of birth, contact details, addresses, admission category, guardians) need
 *       {@code student.personal.read}; without it they are omitted from responses.</li>
 *   <li>Audit and event records never contain personal values (student numbers and codes only).</li>
 * </ul>
 */
@Component
public class StudentAccess {

    static final String READ = Permission.STUDENT_READ.key();
    static final String PERSONAL = Permission.STUDENT_PERSONAL_READ.key();
    static final String MANAGE = Permission.STUDENT_MANAGE.key();
    static final String DOC_READ = Permission.STUDENT_DOCUMENT_READ.key();
    static final String DOC_MANAGE = Permission.STUDENT_DOCUMENT_MANAGE.key();
    static final String ACCOUNT = Permission.STUDENT_ACCOUNT_MANAGE.key();

    public record Unit(UUID id, String type, String code, String name, String path, String status, UUID parentId) {}

    /** Minimal row used for authorization of student-level operations. */
    public record StudentRef(UUID id, String studentNo, UUID institutionId, UUID scopeUnitId, String status, boolean active, long version, UUID userAccountId) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final ValkeyJsonCache cache;
    private final FieldCipher cipher;

    StudentAccess(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, ValkeyJsonCache cache, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.cache = cache;
        this.cipher = cipher;
    }

    // ------------------------------------------------------------------------------------------------ units

    Optional<Unit> unit(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.sql("SELECT id, unit_type, code, name, path::text, status, parent_id FROM org_unit WHERE id = ?").param(id)
                .query((rs, n) -> new Unit(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, UUID.class))).optional();
    }

    static boolean within(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + ".");
    }

    /** The campus above a department, if any. */
    UUID campusOf(UUID departmentId) {
        return jdbc.sql("""
                SELECT a.id FROM org_unit d JOIN org_unit a ON d.path <@ a.path AND a.unit_type = 'CAMPUS' WHERE d.id = ? LIMIT 1""")
                .param(departmentId).query(UUID.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------------------------------------ students

    StudentRef student(UUID id) {
        return jdbc.sql("SELECT id, student_no, institution_id, scope_unit_id, status, active, version, user_account_id FROM student WHERE id = ?")
                .param(id).query((rs, n) -> new StudentRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getBoolean(6), rs.getLong(7), rs.getObject(8, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Student"));
    }

    /** Locks the student row (serialises lifecycle, enrolment and account changes of one student). */
    StudentRef lock(UUID id) {
        jdbc.sql("SELECT 1 FROM student WHERE id = ? FOR UPDATE").param(id).query(Integer.class).optional().orElseThrow(() -> ApiException.notFound("Student"));
        return student(id);
    }

    StudentRef readable(UUID id) {
        StudentRef s = student(id);
        if (!authz.hasInOrgUnit(READ, s.scopeUnitId())) throw new AccessDeniedException("Student outside scope");
        return s;
    }

    StudentRef manageable(UUID id, boolean lock) {
        StudentRef s = lock ? lock(id) : student(id);
        require(MANAGE, s);
        if (!s.active()) throw ApiException.conflict("student_inactive", "This student profile is deactivated. Activate it first.");
        return s;
    }

    void require(String permission, StudentRef s) {
        if (!authz.hasInOrgUnit(READ, s.scopeUnitId())) throw new AccessDeniedException("Student outside scope");
        if (!authz.hasInOrgUnit(permission, s.scopeUnitId())) {
            audit.recordDenied("student.access_denied", Map.of("studentId", s.id().toString(), "permission", permission, "studentNo", s.studentNo()));
            throw new AccessDeniedException("Missing permission " + permission);
        }
    }

    boolean can(String permission, UUID unitId) {
        return authz.hasInOrgUnit(permission, unitId);
    }

    void requireAt(String permission, UUID unitId) {
        authz.require(permission, unitId);
    }

    void requireAnyRead() {
        if (!authz.hasAnywhere(READ)) throw new AccessDeniedException("Missing permission " + READ);
    }

    /** Appends {@code AND (path <@ roots)}; false when the caller sees no student at all. */
    boolean appendVisibility(String pathExpr, StringBuilder where, List<Object> params) {
        if (!authz.hasAnywhere(READ)) return false;
        if (authz.has(READ)) return true;
        List<String> roots = new ArrayList<>();
        for (UUID id : authz.orgUnitScopesWith(READ)) unit(id).map(Unit::path).ifPresent(roots::add);
        if (roots.isEmpty()) return false;
        where.append(" AND ").append(pathExpr).append(" <@ CAST(? AS ltree[])");
        params.add(roots.toArray(String[]::new));
        return true;
    }

    String scopeKey() {
        if (authz.has(READ)) return "all";
        return String.join(",", new java.util.TreeSet<>(authz.orgUnitScopesWith(READ).stream().map(UUID::toString).toList()));
    }

    /** Recomputes the scope unit: current enrolment department, else open admission department, else institution. */
    void refreshScope(UUID studentId) {
        jdbc.sql("""
                UPDATE student s SET scope_unit_id = coalesce(
                    (SELECT e.department_id FROM enrolment e WHERE e.student_id = s.id AND e.is_current),
                    (SELECT a.department_id FROM admission_record a WHERE a.student_id = s.id AND a.status <> 'CANCELLED'),
                    s.institution_id)
                WHERE s.id = ?""").param(studentId).update();
    }

    // ------------------------------------------------------------------------------------------------ encryption

    String seal(String value, String field) {
        return cipher.encrypt(value, field, TenantContext.requireTenantId());
    }

    String open(String stored, String field) {
        return cipher.decrypt(stored, field, TenantContext.requireTenantId());
    }

    // ------------------------------------------------------------------------------------------------ recording

    /** Audit (no personal values) + outbox + student event + dashboard cache invalidation. */
    void changed(String action, StudentRef s, String summary, Map<String, String> details) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("studentNo", s.studentNo());
        d.putAll(details);
        audit.recordSuccess(action, "student", s.id().toString(), d);
        outbox.append("student", s.id().toString(), action, d);
        event(s.id(), action, summary);
    }

    void event(UUID studentId, String type, String summary) {
        jdbc.sql("INSERT INTO student_event (id, tenant_id, student_id, event_type, summary, actor_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(Ids.newId(), tenant(), studentId, type, summary.length() > 300 ? summary.substring(0, 300) : summary, actor()).update();
        UUID tenant = tenant();
        cache.afterCommit(() -> cache.increment(generationKey(tenant)));
    }

    void auditOnly(String action, StudentRef s, Map<String, String> details) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("studentNo", s.studentNo());
        d.putAll(details);
        audit.recordSuccess(action, "student", s.id().toString(), d);
    }

    long generation() {
        return cache.counter(generationKey(tenant()));
    }

    static String generationKey(UUID tenant) {
        return "ivorovi:students-gen:t:" + tenant;
    }

    static UUID tenant() { return TenantContext.requireTenantId(); }

    static UUID actor() { return CurrentUser.require().userId(); }

    static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    static ApiException invalid(String code, String message) { return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message); }

    static void version(Long given, long current) {
        if (given == null || given != current) throw conflict("concurrent_modification", "This record was changed by someone else. Reload and try again.");
    }
}
