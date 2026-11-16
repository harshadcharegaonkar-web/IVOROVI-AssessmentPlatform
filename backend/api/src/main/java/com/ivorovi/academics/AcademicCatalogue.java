package com.ivorovi.academics;

import com.ivorovi.academics.internal.CalendarService;
import com.ivorovi.academics.internal.DeliveryService;
import com.ivorovi.academics.internal.StructureService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Read-only module API (demo programme S-3) for admissions, analytics and placement: programmes, courses, batches,
 * subjects and academic years. Every call goes through the existing academics services, so the caller's
 * {@code academics.read} scope applies exactly as on the academics screens (access denied outside it).
 */
@Component
public class AcademicCatalogue {

    public record Program(UUID id, String code, String name, UUID ownerOrgUnitId, int totalTerms, String status) {}

    public record Course(UUID id, String code, String name, UUID programId, UUID departmentId, int effectiveTerms, String status) {}

    public record Batch(UUID id, String code, String name, UUID courseId, UUID programId, UUID departmentId, UUID academicYearId, int admissionYear,
                        int expectedGraduationYear, Integer capacity, String status) {}

    public record Subject(UUID id, String code, String name, UUID ownerOrgUnitId, String status) {}

    public record Year(UUID id, String code, String name, UUID ownerOrgUnitId, LocalDate startDate, LocalDate endDate, String status) {}

    private static final int MAX_BATCHES = 500;

    private final StructureService structure;
    private final DeliveryService delivery;
    private final CalendarService calendar;

    AcademicCatalogue(StructureService structure, DeliveryService delivery, CalendarService calendar) {
        this.structure = structure;
        this.delivery = delivery;
        this.calendar = calendar;
    }

    public Program program(UUID id) {
        StructureService.ProgramView p = structure.program(id);
        return new Program(p.id(), p.code(), p.name(), p.ownerOrgUnitId(), p.totalTerms(), p.status().name());
    }

    public Course course(UUID id) {
        StructureService.CourseView c = structure.course(id);
        return new Course(c.id(), c.code(), c.name(), c.programId(), c.departmentId(), c.effectiveTerms(), c.status().name());
    }

    public Batch batch(UUID id) {
        return map(delivery.batch(id));
    }

    /** Batches of a course the caller may read (at most 500), oldest admission year first. */
    public List<Batch> batchesOfCourse(UUID courseId) {
        return delivery.batches(null, null, courseId, null, null, null, 0, MAX_BATCHES, "admissionYear", true).items().stream().map(AcademicCatalogue::map).toList();
    }

    public Subject subject(UUID id) {
        StructureService.SubjectView s = structure.subject(id).subject();
        return new Subject(s.id(), s.code(), s.name(), s.ownerOrgUnitId(), s.status().name());
    }

    public Year year(UUID id) {
        CalendarService.YearView y = calendar.year(id).year();
        return new Year(y.id(), y.code(), y.name(), y.ownerOrgUnitId(), y.startDate(), y.endDate(), y.status().name());
    }

    private static Batch map(DeliveryService.BatchView b) {
        return new Batch(b.id(), b.code(), b.name(), b.courseId(), b.programId(), b.departmentId(), b.academicYearId(), b.admissionYear(),
                b.expectedGraduationYear(), b.capacity(), b.status().name());
    }
}
