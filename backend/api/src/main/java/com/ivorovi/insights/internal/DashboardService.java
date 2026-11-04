package com.ivorovi.insights.internal;

import com.ivorovi.platform.security.Permission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboards of increment 1.7. Every section is computed only when the viewer holds the underlying read permission,
 * and only over the units where they hold it; a section the viewer may not see is {@code null} (never zero). Counts come
 * straight from the records (no stored aggregates), so they are always current; no workload or performance metric is
 * derived where the data model has none.
 */
@Service
public class DashboardService {

    static final String ORG = Permission.ORG_UNIT_READ.key();
    static final String ACADEMICS = Permission.ACADEMICS_READ.key();
    static final String STUDENTS = Permission.STUDENT_READ.key();
    static final String STAFF = Permission.STAFF_READ.key();
    static final String USERS = Permission.USER_READ.key();
    static final String IMPORTS = Permission.STUDENT_IMPORT.key();

    @Schema(name = "InsightGroup")
    public record Group(String id, String label, long count) {}

    @Schema(name = "InsightPoint")
    public record Point(String period, long count) {}

    @Schema(name = "ExecutiveOrganization")
    public record OrgCounts(long universities, long colleges, long campuses, long departments) {}

    @Schema(name = "ExecutiveAcademics")
    public record AcademicCounts(long programs, long courses, long subjects, long activeAcademicYears, long activeBatches) {}

    @Schema(name = "ExecutiveStudents")
    public record StudentCounts(long total, long active, long admissionRecords, long newAdmissions30Days) {}

    @Schema(name = "ExecutiveStaff")
    public record StaffCounts(long total, long active, long faculty) {}

    @Schema(name = "ExecutivePeople")
    public record PeopleCounts(long activeUsers, long pendingInvitations) {}

    @Schema(name = "ExecutiveImports")
    public record ImportActivity(long jobs30Days, long inProgress, long awaitingAction, long completed30Days, long failed30Days, long rowsImported30Days) {}

    @Schema(name = "ExecutiveDashboard")
    public record Executive(OrgCounts organization, AcademicCounts academics, StudentCounts students, StaffCounts staff, PeopleCounts people,
                            ImportActivity imports, Instant generatedAt) {}

    @Schema(name = "FacultyOverview")
    public record FacultyOverview(long offerings, long offeringsWithFaculty, long offeringsWithoutFaculty, long activeAssignments, long staffTeaching,
                                  List<Group> withoutFacultyByDepartment) {}

    @Schema(name = "AcademicDashboard")
    public record Academic(List<Group> programsByLevel, List<Group> programsByInstitution, List<Group> coursesByProgram, List<Group> subjectsByCategory,
                           List<Group> batchesByStatus, List<Group> batchesByAcademicYear, List<Group> enrolmentByAcademicYear,
                           List<Group> enrolmentBySemester, FacultyOverview faculty, Instant generatedAt) {}

    @Schema(name = "StudentInsights")
    public record Students(long total, long active, long prospective, long admitted, long suspended, long withdrawn, long graduated, long alumni,
                           long cancelled, long newAdmissions30Days, List<Point> admissionsByMonth, List<Point> enrolmentsByMonth,
                           List<Point> progressionsByMonth, List<Group> progressionByType, List<Group> byProgram, List<Group> byCourse,
                           List<Group> byBatch, List<Group> bySemester, Instant generatedAt) {}

    @Schema(name = "StaffDashboard")
    public record Staff(long total, long active, long onLeave, long separated, long linkedAccounts, List<Group> byCategory, List<Group> byDepartment,
                        List<Group> byCampus, List<Group> byEmploymentType, FacultyAssignmentSummary assignments, Instant generatedAt) {}

    @Schema(name = "FacultyAssignmentSummary")
    public record FacultyAssignmentSummary(long facultyActive, long facultyTeaching, long facultyWithoutAssignment, long activeAssignments,
                                           long primaryAssignments) {}

    @Schema(name = "ImportDashboardJob")
    public record ImportJob(UUID id, String fileName, String institutionName, String status, String mode, int totalRows, int imported, int failed,
                            int skipped, Instant createdAt, Instant finishedAt, Long durationSeconds) {}

    @Schema(name = "ImportOperations")
    public record Imports(long totalJobs, long completed, long partiallyCompleted, long failed, long cancelled, long inProgress, long awaitingAction,
                          long recordsProcessed, long recordsImported, long recordsUpdated, long recordsRejected, Double averageDurationSeconds,
                          Double medianDurationSeconds, Double p95DurationSeconds, Double averageRowsPerSecond, List<Point> jobsByMonth,
                          List<ImportJob> recent, Instant generatedAt) {}

    private final JdbcClient jdbc;
    private final InsightsScope scope;

    DashboardService(JdbcClient jdbc, InsightsScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    // ================================================================================================ executive

    @Transactional(readOnly = true)
    public Executive executive() {
        OrgCounts org = null;
        if (scope.any(ORG)) {
            Map<String, Long> byType = new LinkedHashMap<>();
            for (Group g : groups("ou.unit_type", "ou.unit_type", "org_unit ou", "ou.status = 'ACTIVE' AND ou.unit_type <> 'ORGANIZATION'", ORG, "ou.path", null, 10)) {
                byType.put(g.id(), g.count());
            }
            org = new OrgCounts(byType.getOrDefault("UNIVERSITY", 0L), byType.getOrDefault("COLLEGE", 0L), byType.getOrDefault("CAMPUS", 0L),
                    byType.getOrDefault("DEPARTMENT", 0L));
        }
        AcademicCounts academics = !scope.any(ACADEMICS) ? null : new AcademicCounts(
                count("program p JOIN org_unit ou ON ou.id = p.owner_org_unit_id", "p.status = 'ACTIVE'", ACADEMICS, "ou.path", null),
                count("course c JOIN org_unit d ON d.id = c.department_id", "c.status = 'ACTIVE'", ACADEMICS, "d.path", null),
                count("subject s JOIN org_unit ou ON ou.id = s.owner_org_unit_id", "s.status = 'ACTIVE'", ACADEMICS, "ou.path", null),
                count("academic_year y LEFT JOIN org_unit ou ON ou.id = y.owner_org_unit_id", "y.status = 'ACTIVE'", ACADEMICS, "ou.path", null),
                count("batch b JOIN course c ON c.id = b.course_id JOIN org_unit d ON d.id = c.department_id", "b.status = 'ACTIVE'", ACADEMICS, "d.path", null));
        StudentCounts students = null;
        if (scope.any(STUDENTS)) {
            String from = "student s JOIN org_unit su ON su.id = s.scope_unit_id";
            students = new StudentCounts(count(from, "true", STUDENTS, "su.path", null),
                    count(from, "s.status IN ('ENROLLED', 'ACTIVE')", STUDENTS, "su.path", null),
                    count("admission_record a JOIN student s ON s.id = a.student_id JOIN org_unit su ON su.id = s.scope_unit_id", "a.status <> 'CANCELLED'",
                            STUDENTS, "su.path", null),
                    count("admission_record a JOIN student s ON s.id = a.student_id JOIN org_unit su ON su.id = s.scope_unit_id",
                            "a.status <> 'CANCELLED' AND a.admission_date >= current_date - 30", STUDENTS, "su.path", null));
        }
        StaffCounts staff = null;
        if (scope.any(STAFF)) {
            staff = new StaffCounts(staffCount("true", null), staffCount("s.employment_status = 'ACTIVE'", null),
                    staffCount("s.employment_status = 'ACTIVE' AND s.category = 'FACULTY'", null));
        }
        PeopleCounts people = null;
        if (scope.any(USERS)) {
            people = new PeopleCounts(userCount("user_account u", "u.status = 'ACTIVE'"),
                    userCount("user_invitation i JOIN user_account u ON u.id = i.user_account_id", "i.status = 'PENDING' AND i.expires_at > now()"));
        }
        ImportActivity imports = null;
        if (scope.any(IMPORTS)) {
            String from = "student_import_job j JOIN org_unit ou ON ou.id = j.institution_id";
            imports = new ImportActivity(count(from, "j.created_at > now() - interval '30 days'", IMPORTS, "ou.path", null),
                    count(from, "j.status IN ('QUEUED', 'VALIDATING', 'PROCESSING')", IMPORTS, "ou.path", null),
                    count(from, "j.status IN ('UPLOADED', 'READY')", IMPORTS, "ou.path", null),
                    count(from, "j.status IN ('COMPLETED', 'PARTIALLY_COMPLETED') AND j.finished_at > now() - interval '30 days'", IMPORTS, "ou.path", null),
                    count(from, "j.status = 'FAILED' AND j.updated_at > now() - interval '30 days'", IMPORTS, "ou.path", null),
                    sum("j.imported_rows", from, "NOT j.rolled_back AND j.finished_at > now() - interval '30 days'", IMPORTS, "ou.path", null));
        }
        return new Executive(org, academics, students, staff, people, imports, Instant.now());
    }

    // ================================================================================================ academic

    @Transactional(readOnly = true)
    public Academic academic(UUID within) {
        if (!scope.any(ACADEMICS)) throw new AccessDeniedException("Missing permission " + ACADEMICS);
        String programs = "program p JOIN org_unit ou ON ou.id = p.owner_org_unit_id";
        String courses = "course c JOIN org_unit d ON d.id = c.department_id JOIN program p ON p.id = c.program_id";
        String batches = "batch b JOIN course c ON c.id = b.course_id JOIN org_unit d ON d.id = c.department_id JOIN academic_year y ON y.id = b.academic_year_id";
        String offerings = "subject_offering o JOIN course c ON c.id = o.course_id JOIN org_unit d ON d.id = c.department_id";
        String live = "o.status IN ('PLANNED', 'ACTIVE')";
        String hasFaculty = "EXISTS (SELECT 1 FROM faculty_assignment f WHERE f.offering_id = o.id AND f.ended_at IS NULL)";
        FacultyOverview faculty = new FacultyOverview(
                count(offerings, live, ACADEMICS, "d.path", within),
                count(offerings, live + " AND " + hasFaculty, ACADEMICS, "d.path", within),
                count(offerings, live + " AND NOT " + hasFaculty, ACADEMICS, "d.path", within),
                count("faculty_assignment f JOIN subject_offering o ON o.id = f.offering_id JOIN course c ON c.id = o.course_id JOIN org_unit d ON d.id = c.department_id",
                        "f.ended_at IS NULL AND " + live, ACADEMICS, "d.path", within),
                countExpr("count(DISTINCT f.staff_profile_id)", "faculty_assignment f JOIN subject_offering o ON o.id = f.offering_id JOIN course c ON c.id = o.course_id "
                        + "JOIN org_unit d ON d.id = c.department_id", "f.ended_at IS NULL AND " + live, ACADEMICS, "d.path", within),
                groups("d.id::text", "d.name", offerings, live + " AND NOT " + hasFaculty, ACADEMICS, "d.path", within, 10));
        List<Group> byYear = null, bySemester = null;
        if (scope.any(STUDENTS)) {
            String enrol = "enrolment e JOIN student s ON s.id = e.student_id JOIN org_unit su ON su.id = s.scope_unit_id JOIN academic_year y ON y.id = e.academic_year_id";
            String cur = "e.is_current AND e.status IN ('ACTIVE', 'SUSPENDED')";
            byYear = groups("y.id::text", "y.name", enrol, cur, STUDENTS, "su.path", within, 12);
            bySemester = groups("e.term_number::text", "'Semester ' || e.term_number", enrol, cur, STUDENTS, "su.path", within, 20);
            bySemester.sort((a, b) -> Integer.compare(Integer.parseInt(a.id()), Integer.parseInt(b.id())));
        }
        return new Academic(
                groups("p.qualification_level", "initcap(lower(p.qualification_level))", programs, "p.status = 'ACTIVE'", ACADEMICS, "ou.path", within, 10),
                groups("ou.id::text", "ou.name", programs, "p.status = 'ACTIVE'", ACADEMICS, "ou.path", within, 15),
                groups("p.id::text", "p.name", courses, "c.status = 'ACTIVE'", ACADEMICS, "d.path", within, 15),
                groups("s.category", "initcap(replace(lower(s.category), '_', ' '))", "subject s JOIN org_unit ou ON ou.id = s.owner_org_unit_id", "s.status = 'ACTIVE'",
                        ACADEMICS, "ou.path", within, 12),
                groups("b.status", "initcap(lower(b.status))", batches, "true", ACADEMICS, "d.path", within, 10),
                groups("y.id::text", "y.name", batches, "b.status IN ('PLANNED', 'ACTIVE')", ACADEMICS, "d.path", within, 12),
                byYear, bySemester, faculty, Instant.now());
    }

    // ================================================================================================ students

    @Transactional(readOnly = true)
    public Students students(UUID within) {
        if (!scope.any(STUDENTS)) throw new AccessDeniedException("Missing permission " + STUDENTS);
        String from = "student s JOIN org_unit su ON su.id = s.scope_unit_id";
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Group g : groups("s.status", "s.status", from, "true", STUDENTS, "su.path", within, 20)) byStatus.put(g.id(), g.count());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        String adm = "admission_record a JOIN student s ON s.id = a.student_id JOIN org_unit su ON su.id = s.scope_unit_id";
        String enrol = "enrolment e JOIN student s ON s.id = e.student_id JOIN org_unit su ON su.id = s.scope_unit_id";
        String current = enrol + " JOIN program p ON p.id = e.program_id JOIN course c ON c.id = e.course_id JOIN batch b ON b.id = e.batch_id";
        String cur = "e.is_current AND e.status IN ('ACTIVE', 'SUSPENDED')";
        List<Group> bySemester = groups("e.term_number::text", "'Semester ' || e.term_number", enrol, cur, STUDENTS, "su.path", within, 20);
        bySemester.sort((a, b) -> Integer.compare(Integer.parseInt(a.id()), Integer.parseInt(b.id())));
        return new Students(total, byStatus.getOrDefault("ENROLLED", 0L) + byStatus.getOrDefault("ACTIVE", 0L), byStatus.getOrDefault("PROSPECTIVE", 0L),
                byStatus.getOrDefault("ADMITTED", 0L), byStatus.getOrDefault("SUSPENDED", 0L), byStatus.getOrDefault("WITHDRAWN", 0L),
                byStatus.getOrDefault("GRADUATED", 0L), byStatus.getOrDefault("ALUMNI", 0L), byStatus.getOrDefault("CANCELLED", 0L),
                count(adm, "a.status <> 'CANCELLED' AND a.admission_date >= current_date - 30", STUDENTS, "su.path", within),
                monthly("a.admission_date", adm, "a.status <> 'CANCELLED'", STUDENTS, "su.path", within),
                monthly("e.enrolled_on", enrol, "e.change_type = 'INITIAL'", STUDENTS, "su.path", within),
                monthly("e.enrolled_on", enrol, "e.change_type IN ('PROGRESSION', 'PROMOTION')", STUDENTS, "su.path", within),
                groups("e.change_type", "initcap(replace(lower(e.change_type), '_', ' '))", enrol,
                        "e.change_type <> 'INITIAL' AND e.enrolled_on >= current_date - 365", STUDENTS, "su.path", within, 10),
                groups("p.id::text", "p.name", current, cur, STUDENTS, "su.path", within, 15),
                groups("c.id::text", "c.name", current, cur, STUDENTS, "su.path", within, 15),
                groups("b.id::text", "b.code", current, cur, STUDENTS, "su.path", within, 15),
                bySemester, Instant.now());
    }

    // ================================================================================================ staff

    @Transactional(readOnly = true)
    public Staff staff(UUID within) {
        if (!scope.any(STAFF)) throw new AccessDeniedException("Missing permission " + STAFF);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Group g : staffGroups("s.employment_status", "s.employment_status", "", "true", within)) byStatus.put(g.id(), g.count());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        String unitJoin = " JOIN org_assignment oa ON oa.staff_profile_id = s.id JOIN org_unit au ON au.id = oa.org_unit_id";
        String teaching = "EXISTS (SELECT 1 FROM faculty_assignment f JOIN subject_offering o ON o.id = f.offering_id "
                + "WHERE f.staff_profile_id = s.id AND f.ended_at IS NULL AND o.status IN ('PLANNED', 'ACTIVE'))";
        long facultyActive = staffCount("s.employment_status = 'ACTIVE' AND s.category = 'FACULTY'", within);
        long facultyTeaching = staffCount("s.employment_status = 'ACTIVE' AND s.category = 'FACULTY' AND " + teaching, within);
        FacultyAssignmentSummary summary = new FacultyAssignmentSummary(facultyActive, facultyTeaching, facultyActive - facultyTeaching,
                staffCountExpr("count(f.id)", " JOIN faculty_assignment f ON f.staff_profile_id = s.id JOIN subject_offering o ON o.id = f.offering_id",
                        "f.ended_at IS NULL AND o.status IN ('PLANNED', 'ACTIVE')", within),
                staffCountExpr("count(f.id)", " JOIN faculty_assignment f ON f.staff_profile_id = s.id JOIN subject_offering o ON o.id = f.offering_id",
                        "f.ended_at IS NULL AND f.is_primary AND o.status IN ('PLANNED', 'ACTIVE')", within));
        return new Staff(total, byStatus.getOrDefault("ACTIVE", 0L), byStatus.getOrDefault("ON_LEAVE", 0L), byStatus.getOrDefault("SEPARATED", 0L),
                staffCount("s.user_account_id IS NOT NULL", within),
                staffGroups("s.category", "initcap(replace(lower(s.category), '_', ' '))", "", "s.employment_status = 'ACTIVE'", within),
                staffGroups("au.id::text", "au.name", unitJoin, "s.employment_status = 'ACTIVE' AND au.unit_type = 'DEPARTMENT'", within),
                staffGroups("cu.id::text", "cu.name", unitJoin + " JOIN org_unit cu ON cu.unit_type = 'CAMPUS' AND au.path <@ cu.path",
                        "s.employment_status = 'ACTIVE'", within),
                staffGroups("coalesce(s.employment_type, 'UNSPECIFIED')", "initcap(lower(coalesce(s.employment_type, 'Not recorded')))", "",
                        "s.employment_status = 'ACTIVE'", within),
                summary, Instant.now());
    }

    // ================================================================================================ imports

    @Transactional(readOnly = true)
    public Imports imports(UUID within, LocalDate from, LocalDate to) {
        if (!scope.any(IMPORTS)) throw new AccessDeniedException("Missing permission " + IMPORTS);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE true");
        if (from != null) { where.append(" AND j.created_at >= ?"); params.add(java.sql.Date.valueOf(from)); }
        if (to != null) { where.append(" AND j.created_at < ?"); params.add(java.sql.Date.valueOf(to.plusDays(1))); }
        if (!scope.append(IMPORTS, "ou.path", within, where, params)) {
            return new Imports(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, List.of(), List.of(), Instant.now());
        }
        String fromSql = " FROM student_import_job j JOIN org_unit ou ON ou.id = j.institution_id";
        Map<String, Object> k = jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE j.status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE j.status = 'PARTIALLY_COMPLETED') AS partial,
                       count(*) FILTER (WHERE j.status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE j.status = 'CANCELLED') AS cancelled,
                       count(*) FILTER (WHERE j.status IN ('QUEUED', 'VALIDATING', 'PROCESSING')) AS running,
                       count(*) FILTER (WHERE j.status IN ('UPLOADED', 'READY')) AS awaiting,
                       coalesce(sum(j.processed_rows) FILTER (WHERE j.phase = 'IMPORT'), 0) AS processed,
                       coalesce(sum(j.imported_rows) FILTER (WHERE NOT j.rolled_back), 0) AS imported,
                       coalesce(sum(j.updated_rows) FILTER (WHERE NOT j.rolled_back), 0) AS updated,
                       coalesce(sum(j.failed_rows + j.invalid_rows + j.conflict_rows) FILTER (WHERE j.phase = 'IMPORT' OR j.status = 'READY'), 0) AS rejected,
                       avg(extract(epoch FROM j.finished_at - j.started_at)) FILTER (WHERE j.phase = 'IMPORT' AND j.finished_at IS NOT NULL AND j.started_at IS NOT NULL) AS avg_s,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM j.finished_at - j.started_at))
                           FILTER (WHERE j.phase = 'IMPORT' AND j.finished_at IS NOT NULL AND j.started_at IS NOT NULL) AS p50_s,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM j.finished_at - j.started_at))
                           FILTER (WHERE j.phase = 'IMPORT' AND j.finished_at IS NOT NULL AND j.started_at IS NOT NULL) AS p95_s,
                       avg((j.imported_rows + j.updated_rows) / nullif(extract(epoch FROM j.finished_at - j.started_at), 0))
                           FILTER (WHERE j.phase = 'IMPORT' AND j.finished_at IS NOT NULL AND j.started_at IS NOT NULL AND j.imported_rows + j.updated_rows > 0) AS rps""" + fromSql + where)
                .params(params).query().singleRow();
        List<Point> byMonth = jdbc.sql("SELECT to_char(date_trunc('month', j.created_at), 'YYYY-MM') AS m, count(*)" + fromSql + where
                        + " AND j.created_at >= date_trunc('month', now()) - interval '11 months' GROUP BY 1 ORDER BY 1")
                .params(params).query((rs, n) -> new Point(rs.getString(1), rs.getLong(2))).list();
        List<ImportJob> recent = jdbc.sql("""
                SELECT j.id, j.file_name, ou.name AS inst, j.status, j.mode, j.total_rows, j.imported_rows + j.updated_rows AS ok, j.failed_rows, j.skipped_rows,
                       j.created_at, j.finished_at, extract(epoch FROM j.finished_at - j.started_at)::bigint AS dur""" + fromSql + where
                        + " ORDER BY j.created_at DESC LIMIT 10")
                .params(params).query((rs, n) -> new ImportJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getTimestamp(10).toInstant(),
                        rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(), (Long) rs.getObject(12))).list();
        return new Imports(num(k.get("total")), num(k.get("completed")), num(k.get("partial")), num(k.get("failed")), num(k.get("cancelled")),
                num(k.get("running")), num(k.get("awaiting")), num(k.get("processed")), num(k.get("imported")), num(k.get("updated")), num(k.get("rejected")),
                dbl(k.get("avg_s")), dbl(k.get("p50_s")), dbl(k.get("p95_s")), dbl(k.get("rps")), fill(byMonth), recent, Instant.now());
    }

    // ================================================================================================ helpers

    private long count(String from, String condition, String permission, String pathExpr, UUID within) {
        return countExpr("count(*)", from, condition, permission, pathExpr, within);
    }

    private long countExpr(String expr, String from, String condition, String permission, String pathExpr, UUID within) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(condition);
        if (!scope.append(permission, pathExpr, within, where, params)) return 0;
        return jdbc.sql("SELECT " + expr + " FROM " + from + where).params(params).query(Long.class).single();
    }

    private long sum(String expr, String from, String condition, String permission, String pathExpr, UUID within) {
        return countExpr("coalesce(sum(" + expr + "), 0)", from, condition, permission, pathExpr, within);
    }

    private List<Group> groups(String idExpr, String labelExpr, String from, String condition, String permission, String pathExpr, UUID within, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(condition);
        if (!scope.append(permission, pathExpr, within, where, params)) return new ArrayList<>();
        params.add(limit);
        return new ArrayList<>(jdbc.sql("SELECT " + idExpr + " AS id, " + labelExpr + " AS label, count(*) AS n FROM " + from + where
                        + " GROUP BY 1, 2 ORDER BY 3 DESC, 2 LIMIT ?").params(params)
                .query((rs, n) -> new Group(rs.getString(1), rs.getString(2), rs.getLong(3))).list());
    }

    /** Last 12 months (oldest first, zero-filled). */
    private List<Point> monthly(String dateExpr, String from, String condition, String permission, String pathExpr, UUID within) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(condition).append(" AND ").append(dateExpr)
                .append(" >= date_trunc('month', current_date) - interval '11 months'");
        if (!scope.append(permission, pathExpr, within, where, params)) return fill(List.of());
        return fill(jdbc.sql("SELECT to_char(date_trunc('month', " + dateExpr + "), 'YYYY-MM') AS m, count(*) FROM " + from + where + " GROUP BY 1 ORDER BY 1")
                .params(params).query((rs, n) -> new Point(rs.getString(1), rs.getLong(2))).list());
    }

    static List<Point> fill(List<Point> points) {
        Map<String, Long> m = new LinkedHashMap<>();
        YearMonth start = YearMonth.now().minusMonths(11);
        for (int i = 0; i < 12; i++) m.put(start.plusMonths(i).toString(), 0L);
        for (Point p : points) if (m.containsKey(p.period())) m.put(p.period(), p.count());
        return m.entrySet().stream().map(e -> new Point(e.getKey(), e.getValue())).toList();
    }

    private static final String STAFF_UNITS = "SELECT oa2.org_unit_id AS unit FROM org_assignment oa2 WHERE oa2.staff_profile_id = s.id";

    private long staffCount(String condition, UUID within) {
        return staffCountExpr("count(DISTINCT s.id)", "", condition, within);
    }

    private long staffCountExpr(String expr, String joins, String condition, UUID within) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(condition);
        if (!scope.appendExists(STAFF, STAFF_UNITS, within, where, params)) return 0;
        return jdbc.sql("SELECT " + expr + " FROM staff_profile s" + joins + where).params(params).query(Long.class).single();
    }

    private List<Group> staffGroups(String idExpr, String labelExpr, String joins, String condition, UUID within) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(condition);
        if (!scope.appendExists(STAFF, STAFF_UNITS, within, where, params)) return new ArrayList<>();
        return new ArrayList<>(jdbc.sql("SELECT " + idExpr + " AS id, " + labelExpr + " AS label, count(DISTINCT s.id) AS n FROM staff_profile s" + joins + where
                        + " GROUP BY 1, 2 ORDER BY 3 DESC, 2 LIMIT 15").params(params)
                .query((rs, n) -> new Group(rs.getString(1), rs.getString(2), rs.getLong(3))).list());
    }

    private static final String USER_UNITS = """
            SELECT oa.org_unit_id AS unit FROM org_assignment oa WHERE oa.user_account_id = u.id
            UNION SELECT oa.org_unit_id FROM org_assignment oa JOIN staff_profile sp ON sp.id = oa.staff_profile_id WHERE sp.user_account_id = u.id
            UNION SELECT ra.scope_id FROM role_assignment ra WHERE ra.user_account_id = u.id AND ra.revoked_at IS NULL AND ra.scope_type = 'ORG_UNIT'""";

    private long userCount(String from, String condition) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(condition);
        if (!scope.appendExists(USERS, USER_UNITS, null, where, params)) return 0;
        return jdbc.sql("SELECT count(*) FROM " + from + where).params(params).query(Long.class).single();
    }

    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    private static Double dbl(Object o) { return o == null ? null : Math.round(((Number) o).doubleValue() * 10) / 10.0; }
}
