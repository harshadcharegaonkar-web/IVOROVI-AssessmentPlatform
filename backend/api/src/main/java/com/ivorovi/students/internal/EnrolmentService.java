package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.actor;
import static com.ivorovi.students.internal.StudentAccess.conflict;
import static com.ivorovi.students.internal.StudentAccess.invalid;
import static com.ivorovi.students.internal.StudentAccess.tenant;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.web.ApiException;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Academic enrolments. A student has at most one current enrolment; every change (progression to the next term,
 * promotion to the next academic year, batch or course transfer) closes the current row and opens a new one linked to
 * it, so earlier academic history is never overwritten. Suspension keeps the current row (marked SUSPENDED);
 * withdrawal and graduation close it.
 */
@Service
public class EnrolmentService {

    public enum ChangeType { INITIAL, PROGRESSION, PROMOTION, BATCH_TRANSFER, COURSE_TRANSFER }

    @Schema(name = "StudentEnrolment")
    public record EnrolmentView(UUID id, String institutionName, String campusName, UUID departmentId, String departmentName, UUID programId,
                                String programName, UUID courseId, String courseName, UUID batchId, String batchCode, String batchName, UUID academicYearId,
                                String academicYearName, UUID termId, String termName, int termNumber, LocalDate enrolledOn, LocalDate endedOn, String status,
                                ChangeType changeType, boolean current, String reason, UUID previousEnrolmentId, Instant createdAt, String actorName) {}

    public record Command(ChangeType type, UUID batchId, UUID academicYearId, UUID termId, Integer termNumber, LocalDate effectiveOn, String reason) {}

    private final JdbcClient jdbc;
    private final StudentAccess access;
    private final AcademicRefs refs;
    private final StudentLifecycle lifecycle;
    private final AdmissionService admissions;

    EnrolmentService(JdbcClient jdbc, StudentAccess access, AcademicRefs refs, StudentLifecycle lifecycle, AdmissionService admissions) {
        this.jdbc = jdbc;
        this.access = access;
        this.refs = refs;
        this.lifecycle = lifecycle;
        this.admissions = admissions;
    }

    /*
     * KI-86: filter the student's enrolments first, then look each name up by primary key. The former nine-way join let the
     * planner, without statistics (after a restore or crash recovery), cross-join program × course × batch × year before
     * filtering on the student (4 ms → minutes). Per-row primary-key lookups cannot be reordered that way.
     */
    private static final String SELECT = """
            SELECT e.*,
                   (SELECT name FROM org_unit WHERE id = e.institution_id) AS institution_name,
                   (SELECT name FROM org_unit WHERE id = e.campus_id) AS campus_name,
                   (SELECT name FROM org_unit WHERE id = e.department_id) AS department_name,
                   (SELECT name FROM program WHERE id = e.program_id) AS program_name,
                   (SELECT name FROM course WHERE id = e.course_id) AS course_name,
                   (SELECT code FROM batch WHERE id = e.batch_id) AS batch_code,
                   (SELECT name FROM batch WHERE id = e.batch_id) AS batch_name,
                   (SELECT name FROM academic_year WHERE id = e.academic_year_id) AS year_name,
                   (SELECT name FROM academic_term WHERE id = e.term_id) AS term_name,
                   (SELECT display_name FROM user_account WHERE id = e.created_by) AS actor_name
            FROM enrolment e""";

    @Transactional(readOnly = true)
    public List<EnrolmentView> history(UUID studentId) {
        access.readable(studentId);
        return jdbc.sql(SELECT + " WHERE e.student_id = ? ORDER BY e.is_current DESC, e.enrolled_on DESC, e.created_at DESC").param(studentId)
                .query(EnrolmentService::map).list();
    }

    Optional<EnrolmentView> currentFor(UUID studentId) {
        return jdbc.sql(SELECT + " WHERE e.student_id = ? AND e.is_current").param(studentId).query(EnrolmentService::map).optional();
    }

    @Transactional
    public UUID change(UUID studentId, Command c) {
        StudentAccess.StudentRef s = access.manageable(studentId, true);
        if (c.type() == null) throw ApiException.badRequest("invalid_type", "Choose the kind of enrolment change.");
        LocalDate effective = c.effectiveOn() == null ? LocalDate.now() : c.effectiveOn();
        Optional<EnrolmentView> current = currentFor(studentId);
        AcademicRefs.Batch batch = refs.batch(c.batchId(), "batchId");
        AcademicRefs.Course course = refs.course(batch.courseId(), "batchId");
        AcademicRefs.Program program = refs.program(course.programId(), "batchId");
        if (!"ACTIVE".equals(course.status()) || !"ACTIVE".equals(program.status())) throw invalid("course_inactive", course.name() + " or its program is inactive.");
        if (!Set.of("ACTIVE", "PLANNED").contains(batch.status())) throw invalid("batch_not_active", batch.name() + " is not open for enrolment.");
        String institutionPath = access.unit(s.institutionId()).orElseThrow().path();
        if (!StudentAccess.within(program.ownerPath(), institutionPath)) {
            throw invalid("program_outside_institution", program.name() + " is not offered by the student's institution.");
        }
        access.requireAt(StudentAccess.MANAGE, course.departmentId());
        UUID admissionId = null;
        String reason = StudentService.optional(c.reason(), 500);
        int term;
        UUID yearId;
        switch (c.type()) {
            case INITIAL -> {
                if (current.isPresent()) throw conflict("already_enrolled", "The student already has a current enrolment; use progression or a transfer.");
                if (!"ADMITTED".equals(s.status())) throw conflict("student_not_admitted", "Only admitted students can be enrolled.");
                AdmissionService.Row admission = admissions.confirmedFor(studentId)
                        .orElseThrow(() -> conflict("admission_required", "Confirm the student's admission before enrolling."));
                if (!admission.courseId().equals(course.id())) {
                    throw invalid("course_mismatch", "The batch belongs to another course than the admission. Enrol in a batch of the admitted course.");
                }
                admissionId = admission.id();
                term = c.termNumber() == null ? admission.entryTerm() : c.termNumber();
                yearId = c.academicYearId() == null ? batch.yearId() : c.academicYearId();
                if (reason == null) reason = "Initial enrolment";
            }
            case PROGRESSION, PROMOTION, BATCH_TRANSFER, COURSE_TRANSFER -> {
                EnrolmentView cur = current.orElseThrow(() -> conflict("not_enrolled", "The student has no current enrolment."));
                if (!Set.of("ENROLLED", "ACTIVE").contains(s.status())) {
                    throw conflict("student_not_active", "Only enrolled or active students can progress or transfer (current status: " + s.status().toLowerCase() + ").");
                }
                if (effective.isBefore(cur.enrolledOn())) throw invalid("invalid_effective_date", "The change cannot take effect before the current enrolment started.");
                admissionId = jdbc.sql("SELECT admission_id FROM enrolment WHERE id = ?").param(cur.id()).query(UUID.class).optional().orElse(null);
                term = c.termNumber() == null ? cur.termNumber() : c.termNumber();
                yearId = c.academicYearId() == null ? cur.academicYearId() : c.academicYearId();
                switch (c.type()) {
                    case PROGRESSION -> {
                        if (!batch.id().equals(cur.batchId())) throw invalid("batch_mismatch", "Progression keeps the batch; use a batch transfer to change it.");
                        if (c.termNumber() == null || term <= cur.termNumber()) throw invalid("invalid_term_number", "Progression moves to a later term than " + cur.termNumber() + ".");
                    }
                    case PROMOTION -> {
                        if (c.academicYearId() == null || c.academicYearId().equals(cur.academicYearId())) {
                            throw invalid("invalid_academicYearId", "Promotion needs the next academic year.");
                        }
                        AcademicRefs.Year from = refs.year(cur.academicYearId(), "academicYearId");
                        AcademicRefs.Year to = refs.year(c.academicYearId(), "academicYearId");
                        if (!to.start().isAfter(from.start())) throw invalid("invalid_academicYearId", "Promotion moves to a later academic year.");
                        if (c.termNumber() == null || term <= cur.termNumber()) throw invalid("invalid_term_number", "Promotion moves to a later term than " + cur.termNumber() + ".");
                        if (!course.id().equals(cur.courseId())) throw invalid("course_mismatch", "Promotion keeps the course; use a course transfer to change it.");
                    }
                    case BATCH_TRANSFER -> {
                        if (!course.id().equals(cur.courseId())) throw invalid("course_mismatch", "A batch transfer stays within the course; use a course transfer.");
                        if (batch.id().equals(cur.batchId())) throw invalid("batch_mismatch", "Choose a different batch.");
                        if (reason == null) throw ApiException.badRequest("reason_required", "Give a reason for the transfer.");
                    }
                    case COURSE_TRANSFER -> {
                        if (course.id().equals(cur.courseId())) throw invalid("course_mismatch", "Choose a batch of a different course.");
                        if (reason == null) throw ApiException.badRequest("reason_required", "Give a reason for the transfer.");
                        access.requireAt(StudentAccess.MANAGE, cur.departmentId());
                    }
                    default -> { }
                }
            }
            default -> throw ApiException.badRequest("invalid_type", "Unknown change.");
        }
        if (term < 1 || term > course.effectiveTerms()) throw invalid("invalid_term_number", "Choose a term from 1 to " + course.effectiveTerms() + " of " + course.name() + ".");
        AcademicRefs.Year year = refs.year(yearId, "academicYearId");
        AcademicRefs.requireApplicable(year, program, "academicYearId");
        UUID termId = c.termId();
        if (termId != null) {
            AcademicRefs.Term t = refs.term(termId, "termId");
            if (!t.yearId().equals(year.id())) throw invalid("term_not_in_year", t.name() + " does not belong to " + year.name() + ".");
            if ("COMPLETED".equals(t.status())) throw invalid("term_completed", t.name() + " is completed.");
        }
        if (batch.capacity() != null) {
            // serialise enrolments into the same batch so simultaneous requests cannot exceed the capacity
            jdbc.sql("SELECT 1 FROM batch WHERE id = ? FOR UPDATE").param(batch.id()).query(Integer.class).single();
            long seats = jdbc.sql("SELECT count(*) FROM enrolment WHERE batch_id = ? AND is_current AND status IN ('ACTIVE', 'SUSPENDED') AND student_id <> ?")
                    .params(batch.id(), studentId).query(Long.class).single();
            if (seats >= batch.capacity()) throw conflict("batch_full", batch.name() + " is full (capacity " + batch.capacity() + ").");
        }
        UUID previous = null;
        if (current.isPresent()) {
            previous = current.get().id();
            String closed = c.type() == ChangeType.BATCH_TRANSFER || c.type() == ChangeType.COURSE_TRANSFER ? "TRANSFERRED" : "COMPLETED";
            jdbc.sql("UPDATE enrolment SET is_current = false, status = ?, ended_on = ? WHERE id = ?").params(closed, effective, previous).update();
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO enrolment (id, tenant_id, student_id, admission_id, institution_id, campus_id, department_id, program_id, course_id, batch_id,
                    academic_year_id, term_id, term_number, enrolled_on, status, change_type, is_current, previous_enrolment_id, reason, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, true, ?, ?, ?)""")
                .params(id, tenant(), studentId, admissionId, s.institutionId(), access.campusOf(course.departmentId()), course.departmentId(), program.id(),
                        course.id(), batch.id(), year.id(), termId, term, effective, c.type().name(), previous, reason, actor()).update();
        access.refreshScope(studentId);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("type", c.type().name());
        d.put("batch", batch.code());
        d.put("termNumber", String.valueOf(term));
        d.put("effectiveOn", effective.toString());
        if (reason != null) d.put("reason", reason);
        access.changed("student.enrolment." + c.type().name().toLowerCase(), s, label(c.type()) + ": " + batch.code() + ", term " + term, d);
        if (c.type() == ChangeType.INITIAL) lifecycle.transition(access.student(studentId), Status.ENROLLED, effective, "Enrolled in " + batch.code());
        return id;
    }

    /** Lifecycle effects on the current enrolment: SUSPENDED/ACTIVE keep it current; WITHDRAWN/GRADUATED close it. */
    void markCurrent(UUID studentId, String status, LocalDate endedOn, String reason) {
        if (endedOn == null) {
            jdbc.sql("UPDATE enrolment SET status = ? WHERE student_id = ? AND is_current").params(status, studentId).update();
        } else {
            jdbc.sql("UPDATE enrolment SET status = ?, ended_on = greatest(enrolled_on, ?), is_current = false, reason = coalesce(reason, ?) WHERE student_id = ? AND is_current")
                    .params(status, endedOn, reason, studentId).update();
        }
    }

    static String label(ChangeType t) {
        return switch (t) {
            case INITIAL -> "Enrolled";
            case PROGRESSION -> "Progressed to the next term";
            case PROMOTION -> "Promoted to the next academic year";
            case BATCH_TRANSFER -> "Transferred to another batch";
            case COURSE_TRANSFER -> "Transferred to another course";
        };
    }

    private static EnrolmentView map(ResultSet rs, int n) throws SQLException {
        return new EnrolmentView(rs.getObject("id", UUID.class), rs.getString("institution_name"), rs.getString("campus_name"), rs.getObject("department_id", UUID.class),
                rs.getString("department_name"), rs.getObject("program_id", UUID.class), rs.getString("program_name"), rs.getObject("course_id", UUID.class),
                rs.getString("course_name"), rs.getObject("batch_id", UUID.class), rs.getString("batch_code"), rs.getString("batch_name"),
                rs.getObject("academic_year_id", UUID.class), rs.getString("year_name"), rs.getObject("term_id", UUID.class), rs.getString("term_name"),
                rs.getInt("term_number"), rs.getObject("enrolled_on", LocalDate.class), rs.getObject("ended_on", LocalDate.class), rs.getString("status"),
                ChangeType.valueOf(rs.getString("change_type")), rs.getBoolean("is_current"), rs.getString("reason"), rs.getObject("previous_enrolment_id", UUID.class),
                StudentService.instant(rs, "created_at"), rs.getString("actor_name"));
    }
}
