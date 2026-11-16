package com.ivorovi.students;

import com.ivorovi.students.internal.AdmissionConversion;
import com.ivorovi.students.internal.StudentService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Module API for the admissions module (demo programme S-2): turns an accepted admission application into a student
 * through the existing student, admission and enrolment services, with all their checks. The caller needs
 * {@code student.manage} at the institution and the course's department, and {@code student.personal.read} when
 * personal details or a category are passed. Idempotent per application id: a repeated call returns the first result
 * with {@code created = false}.
 */
@Component
public class StudentAdmissions {

    public enum Gender { FEMALE, MALE, OTHER, UNDISCLOSED }

    /** Personal details as entered by the applicant; stored encrypted by the students module. */
    public record Personal(LocalDate dateOfBirth, Gender gender, String nationality, String personalEmail, String mobile, String address) {}

    /**
     * @param studentNo     null when the institution generates student IDs; otherwise required and checked against its format
     * @param admissionType built-in or organization-defined admission type code (e.g. {@code REGULAR})
     * @param batchId       optional: when given, the student is also enrolled in this batch of the admitted course
     */
    public record Conversion(UUID applicationId, UUID institutionId, String studentNo, String admissionNo, LocalDate admissionDate, String admissionType,
                             String admissionCategory, UUID entryAcademicYearId, UUID programId, UUID courseId, UUID batchId, String firstName,
                             String middleName, String lastName, Personal personal) {}

    public record Converted(UUID studentId, String studentNo, UUID admissionId, UUID enrolmentId, boolean created) {}

    private final AdmissionConversion conversion;

    StudentAdmissions(AdmissionConversion conversion) {
        this.conversion = conversion;
    }

    public Converted convert(Conversion c) {
        Personal p = c.personal();
        StudentService.PersonalCommand personal = p == null ? null : new StudentService.PersonalCommand(p.dateOfBirth(),
                p.gender() == null ? null : StudentService.Gender.valueOf(p.gender().name()), p.nationality(), p.personalEmail(), p.mobile(), null,
                p.address(), null);
        AdmissionConversion.Result r = conversion.convert(new AdmissionConversion.Request(c.applicationId(), c.institutionId(), c.studentNo(), c.admissionNo(),
                c.admissionDate(), c.admissionType(), c.admissionCategory(), c.entryAcademicYearId(), c.programId(), c.courseId(), c.batchId(),
                c.firstName(), c.middleName(), c.lastName(), personal));
        return new Converted(r.studentId(), r.studentNo(), r.admissionId(), r.enrolmentId(), r.created());
    }
}
