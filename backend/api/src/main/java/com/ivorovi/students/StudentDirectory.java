package com.ivorovi.students;

import com.ivorovi.students.internal.StudentService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Read-only module API (demo programme S-2) for analytics and placement: student summaries and current enrolment,
 * limited to the students the caller may read ({@code student.read} down the org-unit tree). No personal data: no date
 * of birth, personal contact details, category or guardians. The institutional e-mail is not personal contact data.
 */
@Component
public class StudentDirectory {

    public record Student(UUID id, String studentNo, String fullName, String status, boolean active, UUID institutionId, UUID programId, UUID courseId,
                          UUID batchId, String batchCode, String academicYearName, Integer termNumber) {}

    private final StudentService students;

    StudentDirectory(StudentService students) {
        this.students = students;
    }

    /** Readable students among {@code ids}, plus readable students currently enrolled in {@code batchIds} (at most 5,000). */
    public List<Student> find(Collection<UUID> ids, Collection<UUID> batchIds) {
        return students.directory(ids, batchIds).stream()
                .map(s -> new Student(s.id(), s.studentNo(), s.fullName(), s.status().name(), s.active(), s.institutionId(), s.programId(), s.courseId(),
                        s.batchId(), s.batchCode(), s.academicYearName(), s.termNumber()))
                .toList();
    }

    /** Institutional e-mail addresses (for examination invitations) of readable students; students without one are absent. */
    public Map<UUID, String> institutionalEmails(Collection<UUID> ids) {
        return students.institutionalEmails(ids);
    }
}
