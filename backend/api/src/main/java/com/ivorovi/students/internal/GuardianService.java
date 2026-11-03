package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.actor;
import static com.ivorovi.students.internal.StudentAccess.tenant;
import static com.ivorovi.students.internal.StudentAccess.version;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guardians of a student. Guardian details are personal information: reading them needs {@code student.personal.read},
 * changing them also needs {@code student.manage}. Contact details are stored encrypted. At most one primary guardian.
 */
@Service
public class GuardianService {

    static final Set<String> RELATIONSHIPS = Set.of("MOTHER", "FATHER", "GUARDIAN", "GRANDPARENT", "SIBLING", "SPOUSE", "RELATIVE", "OTHER");

    @Schema(name = "Guardian")
    public record GuardianView(UUID id, String name, String relationship, String mobile, String email, String address, boolean primary, boolean emergency,
                               long version) {}

    public record Command(String name, String relationship, String mobile, String email, String address, Boolean primary, Boolean emergency, Long version) {}

    private final JdbcClient jdbc;
    private final StudentAccess access;

    GuardianService(JdbcClient jdbc, StudentAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** Not read-only: every view of guardian details is audited. */
    @Transactional
    public List<GuardianView> list(UUID studentId) {
        StudentAccess.StudentRef s = access.readable(studentId);
        requirePersonal(s);
        access.auditOnly("student.guardians.viewed", s, Map.of());
        return jdbc.sql("SELECT id, name, relationship, mobile_enc, email_enc, address_enc, is_primary, is_emergency, version FROM guardian WHERE student_id = ? "
                        + "ORDER BY is_primary DESC, created_at").param(studentId)
                .query((rs, n) -> new GuardianView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), access.open(rs.getString(4), "guardian.mobile"),
                        access.open(rs.getString(5), "guardian.email"), access.open(rs.getString(6), "guardian.address"), rs.getBoolean(7), rs.getBoolean(8),
                        rs.getLong(9))).list();
    }

    @Transactional
    public UUID add(UUID studentId, Command c) {
        StudentAccess.StudentRef s = access.manageable(studentId, true);
        requirePersonal(s);
        Fields f = fields(c);
        if (jdbc.sql("SELECT count(*) FROM guardian WHERE student_id = ?").param(studentId).query(Long.class).single() >= 6) {
            throw StudentAccess.conflict("too_many_guardians", "A student can have at most six guardians.");
        }
        boolean primary = Boolean.TRUE.equals(c.primary()) || jdbc.sql("SELECT count(*) FROM guardian WHERE student_id = ?").param(studentId).query(Long.class).single() == 0;
        if (primary) jdbc.sql("UPDATE guardian SET is_primary = false WHERE student_id = ? AND is_primary").param(studentId).update();
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO guardian (id, tenant_id, student_id, name, relationship, mobile_enc, email_enc, address_enc, is_primary, is_emergency, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, tenant(), studentId, f.name, f.relationship, f.mobile, f.email, f.address, primary, Boolean.TRUE.equals(c.emergency()), actor(), actor()).update();
        access.changed("student.guardian.added", s, "Guardian added (" + f.relationship.toLowerCase() + (primary ? ", primary" : "") + ")",
                Map.of("guardianId", id.toString(), "relationship", f.relationship));
        return id;
    }

    @Transactional
    public void update(UUID studentId, UUID guardianId, Command c) {
        StudentAccess.StudentRef s = access.manageable(studentId, true);
        requirePersonal(s);
        long current = jdbc.sql("SELECT version FROM guardian WHERE id = ? AND student_id = ?").params(guardianId, studentId).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("Guardian"));
        version(c.version(), current);
        Fields f = fields(c);
        boolean primary = Boolean.TRUE.equals(c.primary());
        if (primary) jdbc.sql("UPDATE guardian SET is_primary = false WHERE student_id = ? AND is_primary AND id <> ?").params(studentId, guardianId).update();
        jdbc.sql("""
                UPDATE guardian SET name = ?, relationship = ?, mobile_enc = ?, email_enc = ?, address_enc = ?, is_primary = ?, is_emergency = ?, updated_at = now(),
                    updated_by = ?, version = version + 1 WHERE id = ?""")
                .params(f.name, f.relationship, f.mobile, f.email, f.address, primary, Boolean.TRUE.equals(c.emergency()), actor(), guardianId).update();
        access.changed("student.guardian.updated", s, "Guardian updated (" + f.relationship.toLowerCase() + ")", Map.of("guardianId", guardianId.toString()));
    }

    @Transactional
    public void remove(UUID studentId, UUID guardianId) {
        StudentAccess.StudentRef s = access.manageable(studentId, true);
        requirePersonal(s);
        String relationship = jdbc.sql("SELECT relationship FROM guardian WHERE id = ? AND student_id = ?").params(guardianId, studentId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Guardian"));
        jdbc.sql("DELETE FROM guardian WHERE id = ?").param(guardianId).update();
        access.changed("student.guardian.removed", s, "Guardian removed (" + relationship.toLowerCase() + ")", Map.of("guardianId", guardianId.toString()));
    }

    private void requirePersonal(StudentAccess.StudentRef s) {
        if (!access.can(StudentAccess.PERSONAL, s.scopeUnitId())) throw new AccessDeniedException("Guardian details need " + StudentAccess.PERSONAL);
    }

    private record Fields(String name, String relationship, String mobile, String email, String address) {}

    private Fields fields(Command c) {
        String name = StudentService.required("name", c.name(), 200);
        String rel = c.relationship() == null ? "" : c.relationship().trim().toUpperCase();
        if (!RELATIONSHIPS.contains(rel)) throw ApiException.badRequest("invalid_relationship", "Choose the relationship.");
        String mobile = StudentService.phone("mobile", c.mobile());
        String email = StudentService.email("email", c.email());
        if (mobile == null && email == null) throw ApiException.badRequest("invalid_mobile", "Give a mobile number or an email address.");
        return new Fields(name, rel, access.seal(mobile, "guardian.mobile"), access.seal(email, "guardian.email"),
                access.seal(StudentService.optional(c.address(), 500), "guardian.address"));
    }
}
