package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.invalid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read-only view of the academic structure (increment 1.4 tables) used to validate admissions and enrolments.
 * Every lookup is tenant-isolated by RLS; unknown ids are reported as invalid input (422), not as 404.
 */
@Component
class AcademicRefs {

    record Program(UUID id, String code, String name, UUID ownerId, String ownerPath, String status, BigDecimal durationYears) {}
    record Course(UUID id, String code, String name, UUID programId, UUID departmentId, String departmentPath, String status, int effectiveTerms) {}
    record Batch(UUID id, String code, String name, UUID courseId, UUID yearId, String status, Integer capacity) {}
    record Year(UUID id, String code, String name, UUID ownerId, String ownerPath, String status, LocalDate start, LocalDate end) {}
    record Term(UUID id, UUID yearId, String name, int sequence, String status) {}

    private final JdbcClient jdbc;

    AcademicRefs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Program program(UUID id, String field) {
        if (id == null) throw invalid("invalid_" + field, "Choose a program.");
        return jdbc.sql("SELECT p.id, p.code, p.name, p.owner_org_unit_id, ou.path::text, p.status, p.duration_years FROM program p JOIN org_unit ou ON ou.id = p.owner_org_unit_id WHERE p.id = ?")
                .param(id).query((rs, n) -> new Program(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getBigDecimal(7))).optional()
                .orElseThrow(() -> invalid("invalid_" + field, "Choose a program of your organization."));
    }

    Course course(UUID id, String field) {
        if (id == null) throw invalid("invalid_" + field, "Choose a course.");
        return jdbc.sql("""
                SELECT c.id, c.code, c.name, c.program_id, c.department_id, d.path::text, c.status, coalesce(c.total_terms, p.total_terms)
                FROM course c JOIN program p ON p.id = c.program_id JOIN org_unit d ON d.id = c.department_id WHERE c.id = ?""")
                .param(id).query((rs, n) -> new Course(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getInt(8))).optional()
                .orElseThrow(() -> invalid("invalid_" + field, "Choose a course of your organization."));
    }

    Batch batch(UUID id, String field) {
        if (id == null) throw invalid("invalid_" + field, "Choose a batch.");
        return jdbc.sql("SELECT id, code, name, course_id, academic_year_id, status, capacity FROM batch WHERE id = ?")
                .param(id).query((rs, n) -> new Batch(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getString(6), (Integer) rs.getObject(7))).optional()
                .orElseThrow(() -> invalid("invalid_" + field, "Choose a batch of your organization."));
    }

    Year year(UUID id, String field) {
        if (id == null) throw invalid("invalid_" + field, "Choose an academic year.");
        return jdbc.sql("SELECT y.id, y.code, y.name, y.owner_org_unit_id, ou.path::text, y.status, y.start_date, y.end_date FROM academic_year y LEFT JOIN org_unit ou ON ou.id = y.owner_org_unit_id WHERE y.id = ?")
                .param(id).query((rs, n) -> new Year(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class))).optional()
                .orElseThrow(() -> invalid("invalid_" + field, "Choose an academic year of your organization."));
    }

    Term term(UUID id, String field) {
        return jdbc.sql("SELECT id, academic_year_id, name, sequence, status FROM academic_term WHERE id = ?")
                .param(id).query((rs, n) -> new Term(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4), rs.getString(5)))
                .optional().orElseThrow(() -> invalid("invalid_" + field, "Choose a term of the academic calendar."));
    }

    /** Organization-wide years apply everywhere; owned years apply to their institution and everything below it. */
    static boolean applies(Year year, Program program) {
        return year.ownerPath() == null || StudentAccess.within(program.ownerPath(), year.ownerPath());
    }

    static void requireApplicable(Year year, Program program, String field) {
        if (!applies(year, program)) throw invalid("year_not_applicable", year.name() + " does not apply to " + program.name() + ".");
        if ("COMPLETED".equals(year.status())) throw invalid(field.equals("entryAcademicYearId") ? "year_completed" : "year_completed", year.name() + " is completed.");
    }
}
