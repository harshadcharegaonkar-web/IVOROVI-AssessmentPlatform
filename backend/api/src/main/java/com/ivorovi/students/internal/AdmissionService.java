package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.actor;
import static com.ivorovi.students.internal.StudentAccess.conflict;
import static com.ivorovi.students.internal.StudentAccess.invalid;
import static com.ivorovi.students.internal.StudentAccess.tenant;
import static com.ivorovi.students.internal.StudentAccess.version;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.students.internal.StudentService.OptionKind;
import com.ivorovi.students.internal.StudentService.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admission records, kept separate from the profile. One open (non-cancelled) admission per student; confirming it
 * moves a prospective student to ADMITTED. This is not the admissions application/screening workflow (later module).
 */
@Service
public class AdmissionService {

    public enum AdmissionStatus { PROVISIONAL, CONFIRMED, CANCELLED }

    @Schema(name = "StudentAdmission")
    public record AdmissionView(UUID id, String admissionNo, LocalDate admissionDate, String admissionType, String admissionTypeLabel,
                                String admissionCategory, String admissionCategoryLabel, UUID entryAcademicYearId, String entryAcademicYearName,
                                UUID programId, String programName, UUID courseId, String courseName, UUID institutionId, String institutionName,
                                UUID departmentId, String departmentName, int entryTermNumber, AdmissionStatus status, String previousInstitutionName,
                                String previousInstitutionDetails, String statusReason, boolean enrolled, Instant createdAt, long version) {}

    public record Command(String admissionNo, LocalDate admissionDate, String admissionType, String admissionCategory, UUID entryAcademicYearId,
                          UUID programId, UUID courseId, Integer entryTermNumber, AdmissionStatus status, String previousInstitutionName,
                          String previousInstitutionDetails, Long version) {}

    private final JdbcClient jdbc;
    private final StudentAccess access;
    private final AcademicRefs refs;
    private final StudentOptions options;
    private final StudentLifecycle lifecycle;

    AdmissionService(JdbcClient jdbc, StudentAccess access, AcademicRefs refs, StudentOptions options, StudentLifecycle lifecycle) {
        this.jdbc = jdbc;
        this.access = access;
        this.refs = refs;
        this.options = options;
        this.lifecycle = lifecycle;
    }

    private static final String SELECT = """
            SELECT a.*, y.name AS year_name, p.name AS program_name, c.name AS course_name, i.name AS institution_name, d.name AS department_name,
                   EXISTS (SELECT 1 FROM enrolment e WHERE e.admission_id = a.id) AS enrolled
            FROM admission_record a JOIN academic_year y ON y.id = a.entry_academic_year_id JOIN program p ON p.id = a.program_id
            JOIN course c ON c.id = a.course_id JOIN org_unit i ON i.id = a.institution_id JOIN org_unit d ON d.id = a.department_id""";

    @Transactional(readOnly = true)
    public List<AdmissionView> list(UUID studentId) {
        StudentAccess.StudentRef s = access.readable(studentId);
        boolean personal = access.can(StudentAccess.PERSONAL, s.scopeUnitId());
        return jdbc.sql(SELECT + " WHERE a.student_id = ? ORDER BY a.created_at DESC").param(studentId).query((rs, n) -> map(rs, personal)).list();
    }

    Optional<AdmissionView> currentFor(UUID studentId, boolean personal) {
        return jdbc.sql(SELECT + " WHERE a.student_id = ? ORDER BY (a.status <> 'CANCELLED') DESC, a.created_at DESC LIMIT 1").param(studentId)
                .query((rs, n) -> map(rs, personal)).optional();
    }

    @Transactional
    public UUID create(UUID studentId, Command c) {
        StudentAccess.StudentRef s = access.manageable(studentId, true);
        if (!List.of("PROSPECTIVE", "ADMITTED").contains(s.status())) {
            throw conflict("student_not_admissible", "Admission records can be added only for prospective or admitted students.");
        }
        long open = jdbc.sql("SELECT count(*) FROM admission_record WHERE student_id = ? AND status <> 'CANCELLED'").param(studentId).query(Long.class).single();
        if (open > 0) throw conflict("admission_exists", "This student already has an admission record. Cancel it before recording another.");
        Validated v = validate(s, c);
        AdmissionStatus status = c.status() == null ? AdmissionStatus.CONFIRMED : c.status();
        if (status == AdmissionStatus.CANCELLED) throw ApiException.badRequest("invalid_status", "A new admission is provisional or confirmed.");
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO admission_record (id, tenant_id, student_id, admission_no, admission_date, admission_type, admission_category,
                        entry_academic_year_id, program_id, course_id, institution_id, department_id, entry_term_number, status,
                        previous_institution_name, previous_institution_details, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), studentId, v.no, c.admissionDate(), c.admissionType(), v.category, v.year.id(), v.program.id(), v.course.id(),
                            s.institutionId(), v.course.departmentId(), v.entryTerm, status.name(), v.prevName, v.prevDetails, actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw conflict("admission_no_in_use", "Another admission of this institution already uses " + v.no + ".");
        }
        access.refreshScope(studentId);
        access.changed("student.admission.recorded", s, "Admission " + v.no + " recorded (" + options.label(OptionKind.ADMISSION_TYPE, c.admissionType()) + ", "
                + status.name().toLowerCase() + ")", Map.of("admissionNo", v.no, "type", c.admissionType(), "status", status.name(), "courseId", v.course.id().toString()));
        if (status == AdmissionStatus.CONFIRMED && "PROSPECTIVE".equals(s.status())) {
            lifecycle.transition(access.student(studentId), Status.ADMITTED, c.admissionDate().isAfter(LocalDate.now()) ? LocalDate.now() : maxDate(c.admissionDate(), studentId),
                    "Admission " + v.no + " confirmed");
        }
        return id;
    }

    /** The status date can never go backwards: an admission dated before the profile existed takes effect on the profile's date. */
    private LocalDate maxDate(LocalDate date, UUID studentId) {
        LocalDate current = jdbc.sql("SELECT status_effective_on FROM student WHERE id = ?").param(studentId).query(LocalDate.class).single();
        return date.isBefore(current) ? current : date;
    }

    @Transactional
    public void update(UUID studentId, UUID admissionId, Command c) {
        StudentAccess.StudentRef s = access.manageable(studentId, true);
        Row row = row(studentId, admissionId);
        version(c.version(), row.version());
        if (row.status() == AdmissionStatus.CANCELLED) throw conflict("admission_cancelled", "A cancelled admission cannot be changed.");
        if (row.enrolled()) throw conflict("admission_in_use", "The student is enrolled from this admission; it can no longer be changed.");
        Validated v = validate(s, c);
        try {
            jdbc.sql("""
                    UPDATE admission_record SET admission_no = ?, admission_date = ?, admission_type = ?, admission_category = ?, entry_academic_year_id = ?,
                        program_id = ?, course_id = ?, department_id = ?, entry_term_number = ?, previous_institution_name = ?, previous_institution_details = ?,
                        updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""")
                    .params(v.no, c.admissionDate(), c.admissionType(), v.category, v.year.id(), v.program.id(), v.course.id(), v.course.departmentId(),
                            v.entryTerm, v.prevName, v.prevDetails, actor(), admissionId).update();
        } catch (DuplicateKeyException e) {
            throw conflict("admission_no_in_use", "Another admission of this institution already uses " + v.no + ".");
        }
        access.refreshScope(studentId);
        access.changed("student.admission.updated", s, "Admission " + v.no + " updated", Map.of("admissionNo", v.no));
    }

    @Transactional
    public void setStatus(UUID studentId, UUID admissionId, AdmissionStatus to, String reason, long version) {
        StudentAccess.StudentRef s = access.manageable(studentId, true);
        Row row = row(studentId, admissionId);
        version(version, row.version());
        if (to == null || to == AdmissionStatus.PROVISIONAL || to == row.status()) {
            throw conflict("invalid_status_transition", "An admission can be confirmed or cancelled.");
        }
        if (row.status() == AdmissionStatus.CANCELLED) throw conflict("invalid_status_transition", "A cancelled admission cannot change.");
        if (to == AdmissionStatus.CANCELLED) {
            String why = StudentService.required("reason", reason, 500);
            if (row.enrolled()) throw conflict("admission_in_use", "The student is enrolled from this admission. Withdraw the student instead.");
            jdbc.sql("UPDATE admission_record SET status = 'CANCELLED', status_reason = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?")
                    .params(why, actor(), admissionId).update();
            access.refreshScope(studentId);
            access.changed("student.admission.cancelled", s, "Admission " + row.no() + " cancelled", Map.of("admissionNo", row.no(), "reason", why));
            if ("ADMITTED".equals(access.student(studentId).status())) {
                lifecycle.transition(access.student(studentId), Status.CANCELLED, LocalDate.now(), "Admission " + row.no() + " cancelled: " + why);
            }
            return;
        }
        jdbc.sql("UPDATE admission_record SET status = 'CONFIRMED', updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(actor(), admissionId).update();
        access.changed("student.admission.confirmed", s, "Admission " + row.no() + " confirmed", Map.of("admissionNo", row.no()));
        if ("PROSPECTIVE".equals(s.status())) {
            lifecycle.transition(access.student(studentId), Status.ADMITTED, maxDate(LocalDate.now(), studentId), "Admission " + row.no() + " confirmed");
        }
    }

    /** Used when the student is cancelled: the open admission is cancelled with the same reason. */
    void cancelOpen(UUID studentId, String reason) {
        jdbc.sql("UPDATE admission_record SET status = 'CANCELLED', status_reason = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE student_id = ? AND status <> 'CANCELLED'")
                .params(reason, actor(), studentId).update();
    }

    Optional<Row> confirmedFor(UUID studentId) {
        return jdbc.sql("SELECT id, admission_no, status, version, EXISTS (SELECT 1 FROM enrolment e WHERE e.admission_id = a.id), course_id, entry_term_number "
                        + "FROM admission_record a WHERE student_id = ? AND status = 'CONFIRMED'").param(studentId).query(AdmissionService::mapRow).optional();
    }

    record Row(UUID id, String no, AdmissionStatus status, long version, boolean enrolled, UUID courseId, int entryTerm) {}

    private Row row(UUID studentId, UUID admissionId) {
        return jdbc.sql("SELECT id, admission_no, status, version, EXISTS (SELECT 1 FROM enrolment e WHERE e.admission_id = a.id), course_id, entry_term_number "
                        + "FROM admission_record a WHERE id = ? AND student_id = ?").params(admissionId, studentId).query(AdmissionService::mapRow).optional()
                .orElseThrow(() -> ApiException.notFound("Admission"));
    }

    private static Row mapRow(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), AdmissionStatus.valueOf(rs.getString(3)), rs.getLong(4), rs.getBoolean(5),
                rs.getObject(6, UUID.class), rs.getInt(7));
    }

    private record Validated(String no, String category, AcademicRefs.Year year, AcademicRefs.Program program, AcademicRefs.Course course, int entryTerm,
                             String prevName, String prevDetails) {}

    private Validated validate(StudentAccess.StudentRef s, Command c) {
        String no = c.admissionNo() == null ? "" : c.admissionNo().trim();
        if (!no.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$")) throw ApiException.badRequest("invalid_admissionNo", "Use up to 40 letters, digits and . _ / -");
        if (c.admissionDate() == null) throw ApiException.badRequest("invalid_admissionDate", "Enter the admission date.");
        if (c.admissionDate().isAfter(LocalDate.now().plusYears(1)) || c.admissionDate().isBefore(LocalDate.of(1990, 1, 1))) {
            throw ApiException.badRequest("invalid_admissionDate", "Enter a realistic admission date.");
        }
        options.require(OptionKind.ADMISSION_TYPE, c.admissionType(), "admissionType");
        String category = c.admissionCategory() == null || c.admissionCategory().isBlank() ? null : c.admissionCategory();
        if (category != null) {
            if (!access.can(StudentAccess.PERSONAL, s.scopeUnitId()) && !access.can(StudentAccess.PERSONAL, s.institutionId())) {
                throw new org.springframework.security.access.AccessDeniedException("Admission categories are personal information");
            }
            options.require(OptionKind.ADMISSION_CATEGORY, category, "admissionCategory");
        }
        AcademicRefs.Program program = refs.program(c.programId(), "programId");
        AcademicRefs.Course course = refs.course(c.courseId(), "courseId");
        if (!course.programId().equals(program.id())) throw invalid("course_not_in_program", course.name() + " is not a course of " + program.name() + ".");
        if (!"ACTIVE".equals(program.status()) || !"ACTIVE".equals(course.status())) throw invalid("course_inactive", "The program or course is inactive.");
        String institutionPath = access.unit(s.institutionId()).orElseThrow().path();
        if (!StudentAccess.within(program.ownerPath(), institutionPath)) {
            throw invalid("program_outside_institution", program.name() + " is not offered by the student's institution.");
        }
        access.requireAt(StudentAccess.MANAGE, course.departmentId());
        AcademicRefs.Year year = refs.year(c.entryAcademicYearId(), "entryAcademicYearId");
        AcademicRefs.requireApplicable(year, program, "entryAcademicYearId");
        int entryTerm = c.entryTermNumber() == null ? 1 : c.entryTermNumber();
        if (entryTerm < 1 || entryTerm > course.effectiveTerms()) {
            throw invalid("invalid_entry_term", "Choose an entry term from 1 to " + course.effectiveTerms() + ".");
        }
        if ("REGULAR".equals(c.admissionType()) && entryTerm != 1) throw invalid("invalid_entry_term", "A regular admission starts in term 1; use lateral entry or transfer.");
        String prevName = StudentService.optional(c.previousInstitutionName(), 200);
        if ("TRANSFER".equals(c.admissionType()) && prevName == null) {
            throw ApiException.badRequest("invalid_previousInstitutionName", "A transfer admission needs the previous institution.");
        }
        return new Validated(no, category, year, program, course, entryTerm, prevName, StudentService.optional(c.previousInstitutionDetails(), 500));
    }

    private AdmissionView map(ResultSet rs, boolean personal) throws SQLException {
        String type = rs.getString("admission_type");
        String category = personal ? rs.getString("admission_category") : null;
        return new AdmissionView(rs.getObject("id", UUID.class), rs.getString("admission_no"), rs.getObject("admission_date", LocalDate.class), type,
                options.label(OptionKind.ADMISSION_TYPE, type), category, options.label(OptionKind.ADMISSION_CATEGORY, category),
                rs.getObject("entry_academic_year_id", UUID.class), rs.getString("year_name"), rs.getObject("program_id", UUID.class), rs.getString("program_name"),
                rs.getObject("course_id", UUID.class), rs.getString("course_name"), rs.getObject("institution_id", UUID.class), rs.getString("institution_name"),
                rs.getObject("department_id", UUID.class), rs.getString("department_name"), rs.getInt("entry_term_number"),
                AdmissionStatus.valueOf(rs.getString("status")), rs.getString("previous_institution_name"), rs.getString("previous_institution_details"),
                rs.getString("status_reason"), rs.getBoolean("enrolled"), StudentService.instant(rs, "created_at"), rs.getLong("version"));
    }

    static Map<String, String> details(String... kv) {
        Map<String, String> d = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) d.put(kv[i], kv[i + 1]);
        return d;
    }
}
