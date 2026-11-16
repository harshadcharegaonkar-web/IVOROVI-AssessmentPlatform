package com.ivorovi.students.internal;

import com.ivorovi.platform.web.ApiException;
import com.ivorovi.students.internal.AdmissionService.AdmissionStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversion of an accepted admission application (admissions module) into a student: PROSPECTIVE profile → CONFIRMED
 * admission record (→ ADMITTED) → optional initial enrolment, in one transaction, through the existing services and
 * their checks (permissions, numbering, academic validation, batch capacity). Idempotent per application: the
 * admission record keeps the application id ({@code admission_record.source_application_id}, unique per organization).
 */
@Service
public class AdmissionConversion {

    public record Request(UUID applicationId, UUID institutionId, String studentNo, String admissionNo, LocalDate admissionDate, String admissionType, String admissionCategory,
                          UUID entryAcademicYearId, UUID programId, UUID courseId, UUID batchId, String firstName, String middleName, String lastName,
                          StudentService.PersonalCommand personal) {}

    public record Result(UUID studentId, String studentNo, UUID admissionId, UUID enrolmentId, boolean created) {}

    private final JdbcClient jdbc;
    private final StudentService students;
    private final AdmissionService admissions;
    private final EnrolmentService enrolments;

    AdmissionConversion(JdbcClient jdbc, StudentService students, AdmissionService admissions, EnrolmentService enrolments) {
        this.jdbc = jdbc;
        this.students = students;
        this.admissions = admissions;
        this.enrolments = enrolments;
    }

    @Transactional
    public Result convert(Request r) {
        if (r.applicationId() == null) throw ApiException.badRequest("invalid_applicationId", "The application is required.");
        // serialise conversions of the same application (two clicks, two officers)
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").param("admission.convert:" + r.applicationId()).query(Object.class).single();
        Optional<Result> existing = existing(r.applicationId());
        if (existing.isPresent()) return existing.get();
        UUID studentId = students.create(new StudentService.Command(r.institutionId(), r.studentNo(), r.admissionDate() == null ? null : r.admissionDate().getYear(),
                null, null, null, r.firstName(), r.middleName(), r.lastName(), null, r.personal(), null));
        UUID admissionId = admissions.create(studentId, new AdmissionService.Command(r.admissionNo(), r.admissionDate(), r.admissionType(), r.admissionCategory(),
                r.entryAcademicYearId(), r.programId(), r.courseId(), null, AdmissionStatus.CONFIRMED, null, null, null));
        jdbc.sql("UPDATE admission_record SET source_application_id = ? WHERE id = ?").params(r.applicationId(), admissionId).update();
        UUID enrolmentId = null;
        if (r.batchId() != null) {
            enrolments.change(studentId, new EnrolmentService.Command(EnrolmentService.ChangeType.INITIAL, r.batchId(), null, null, null, null, "Enrolled on admission"));
            enrolmentId = jdbc.sql("SELECT id FROM enrolment WHERE student_id = ? AND is_current").param(studentId).query(UUID.class).single();
        }
        String studentNo = jdbc.sql("SELECT student_no FROM student WHERE id = ?").param(studentId).query(String.class).single();
        return new Result(studentId, studentNo, admissionId, enrolmentId, true);
    }

    private Optional<Result> existing(UUID applicationId) {
        return jdbc.sql("""
                SELECT a.student_id, s.student_no, a.id, (SELECT e.id FROM enrolment e WHERE e.admission_id = a.id ORDER BY e.enrolled_on LIMIT 1)
                FROM admission_record a JOIN student s ON s.id = a.student_id WHERE a.source_application_id = ?""").param(applicationId)
                .query((rs, n) -> new Result(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), false))
                .optional();
    }
}
