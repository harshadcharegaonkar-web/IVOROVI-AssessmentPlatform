package com.ivorovi.insights.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tabular.TabularFile;
import com.ivorovi.platform.tabular.TabularWriter;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reusable operational reports: a catalogue of report definitions, each with its columns, filters, sort keys, the read
 * permission of the underlying records and the organizational path used for scoping. Running a report needs
 * {@code report.read} <b>and</b> the underlying permission; rows are limited exactly like the record lists. Personal
 * columns are excluded unless explicitly requested by a holder of the personal-data permission for that record; such
 * runs and every export are audited (filters as identifiers only).
 */
@Service
public class ReportService {

    static final String REPORT = Permission.REPORT_READ.key();
    static final int MAX_EXPORT_ROWS = 50_000;

    @Schema(name = "ReportColumn")
    public record Column(String key, String label, boolean personal) {}

    @Schema(name = "ReportDefinition")
    public record Definition(String key, String title, String description, String permission, List<Column> columns, List<String> filters,
                             List<String> sorts, String dateLabel, boolean personalAvailable, List<String> statuses) {}

    @Schema(name = "ReportResult")
    public record Result(String key, List<Column> columns, List<List<String>> rows, int page, int size, long totalElements, int totalPages,
                         boolean personalIncluded) {}

    public record Query(LocalDate from, LocalDate to, UUID orgUnitId, UUID academicYearId, UUID programId, UUID courseId, UUID batchId, String status,
                        boolean includePersonal, String sort, boolean ascending) {}

    /** How one report is built. */
    private record Spec(Definition def, String select, String from, String base, String scopePath, String staffScope, Map<String, String> filters,
                        String dateExpr, Map<String, String> sorts, RowMapper mapper, String personalPermission, String personalScopeColumn) {}

    private interface RowMapper {
        List<String> map(ResultSet rs, boolean personal) throws SQLException;
    }

    private final JdbcClient jdbc;
    private final InsightsScope scope;
    private final Authz authz;
    private final AuditLog audit;
    private final FieldCipher cipher;
    private final Map<String, Spec> specs = new LinkedHashMap<>();

    ReportService(JdbcClient jdbc, InsightsScope scope, Authz authz, AuditLog audit, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.authz = authz;
        this.audit = audit;
        this.cipher = cipher;
        register();
    }

    // ================================================================================================ catalogue

    private void register() {
        List<String> studentStatuses = List.of("PROSPECTIVE", "ADMITTED", "ENROLLED", "ACTIVE", "SUSPENDED", "WITHDRAWN", "GRADUATED", "ALUMNI", "CANCELLED");
        String studentFrom = """
                student s JOIN org_unit su ON su.id = s.scope_unit_id JOIN org_unit inst ON inst.id = s.institution_id
                LEFT JOIN enrolment e ON e.student_id = s.id AND e.is_current LEFT JOIN program p ON p.id = e.program_id LEFT JOIN course c ON c.id = e.course_id
                LEFT JOIN batch b ON b.id = e.batch_id LEFT JOIN academic_year y ON y.id = e.academic_year_id
                LEFT JOIN org_unit d ON d.id = coalesce(e.department_id, s.scope_unit_id)
                LEFT JOIN admission_record a ON a.student_id = s.id AND a.status <> 'CANCELLED'""";
        Map<String, String> studentFilters = Map.of("academicYearId", "e.academic_year_id = ?", "programId", "e.program_id = ?", "courseId", "e.course_id = ?",
                "batchId", "e.batch_id = ?", "status", "s.status = ?");
        add(new Spec(new Definition("students", "Students", "Students with their admission and current enrolment.", "student.read",
                List.of(col("studentNo", "Student ID"), col("name", "Name"), col("institution", "Institution"), col("department", "Department"),
                        col("program", "Program"), col("course", "Course"), col("batch", "Batch"), col("academicYear", "Academic year"),
                        col("semester", "Semester"), col("status", "Status"), col("profile", "Profile"), col("admissionNo", "Admission number"),
                        col("admissionDate", "Admission date"), personal("dateOfBirth", "Date of birth"), personal("gender", "Gender"),
                        personal("personalEmail", "Personal email"), personal("mobile", "Mobile")),
                List.of("dateRange", "orgUnitId", "academicYearId", "programId", "courseId", "batchId", "status"), List.of("studentNo", "name", "status", "admissionDate"),
                "Admission date", true, studentStatuses),
                """
                s.student_no, concat_ws(' ', s.first_name, s.middle_name, s.last_name) AS name, inst.name AS institution, d.name AS department, p.name AS program,
                c.name AS course, b.code AS batch, y.name AS academic_year, e.term_number, s.status, s.active, a.admission_no, a.admission_date, s.scope_unit_id,
                s.date_of_birth_enc, s.gender, s.personal_email_enc, s.mobile_enc""",
                studentFrom, "true", "su.path", null, studentFilters, "a.admission_date",
                Map.of("studentNo", "lower(s.student_no)", "name", "lower(s.last_name || ' ' || s.first_name)", "status", "s.status", "admissionDate", "a.admission_date"),
                (rs, p) -> {
                    List<String> r = new ArrayList<>(List.of(nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4)), nz(rs.getString(5)),
                            nz(rs.getString(6)), nz(rs.getString(7)), nz(rs.getString(8)), rs.getObject(9) == null ? "" : String.valueOf(rs.getInt(9)), rs.getString(10),
                            rs.getBoolean(11) ? "Active" : "Deactivated", nz(rs.getString(12)), rs.getObject(13) == null ? "" : rs.getObject(13).toString()));
                    if (p) {
                        UUID tenant = TenantContext.requireTenantId();
                        r.add(nz(cipher.decrypt(rs.getString(15), "student.date_of_birth", tenant)));
                        r.add(nz(rs.getString(16)));
                        r.add(nz(cipher.decrypt(rs.getString(17), "student.personal_email", tenant)));
                        r.add(nz(cipher.decrypt(rs.getString(18), "student.mobile", tenant)));
                    }
                    return r;
                }, Permission.STUDENT_PERSONAL_READ.key(), "scope_unit_id"));

        add(new Spec(new Definition("enrolments", "Enrolment history", "Every enrolment change: initial enrolments, progressions, promotions and transfers.",
                "student.read", List.of(col("studentNo", "Student ID"), col("name", "Name"), col("institution", "Institution"), col("program", "Program"),
                        col("course", "Course"), col("batch", "Batch"), col("academicYear", "Academic year"), col("semester", "Semester"),
                        col("change", "Change"), col("enrolledOn", "From"), col("endedOn", "Until"), col("status", "Enrolment status"), col("current", "Current")),
                List.of("dateRange", "orgUnitId", "academicYearId", "programId", "courseId", "batchId", "status"), List.of("enrolledOn", "studentNo", "batch"),
                "Enrolment date", false, List.of("ACTIVE", "SUSPENDED", "COMPLETED", "TRANSFERRED", "WITHDRAWN", "GRADUATED")),
                """
                s.student_no, concat_ws(' ', s.first_name, s.middle_name, s.last_name), inst.name, p.name, c.name, b.code, y.name, e.term_number,
                initcap(replace(lower(e.change_type), '_', ' ')), e.enrolled_on, e.ended_on, e.status, e.is_current""",
                """
                enrolment e JOIN student s ON s.id = e.student_id JOIN org_unit su ON su.id = s.scope_unit_id JOIN org_unit inst ON inst.id = e.institution_id
                JOIN program p ON p.id = e.program_id JOIN course c ON c.id = e.course_id JOIN batch b ON b.id = e.batch_id
                JOIN academic_year y ON y.id = e.academic_year_id""",
                "true", "su.path", null, Map.of("academicYearId", "e.academic_year_id = ?", "programId", "e.program_id = ?", "courseId", "e.course_id = ?",
                        "batchId", "e.batch_id = ?", "status", "e.status = ?"), "e.enrolled_on",
                Map.of("enrolledOn", "e.enrolled_on", "studentNo", "lower(s.student_no)", "batch", "lower(b.code)"),
                (rs, p) -> List.of(nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4)), nz(rs.getString(5)), nz(rs.getString(6)),
                        nz(rs.getString(7)), String.valueOf(rs.getInt(8)), nz(rs.getString(9)), str(rs.getObject(10)), str(rs.getObject(11)), nz(rs.getString(12)),
                        rs.getBoolean(13) ? "Yes" : "No"), null, null));

        add(new Spec(new Definition("staff", "Staff", "Staff profiles with their units. Contact details are not included.", "staff.read",
                List.of(col("staffNo", "Staff number"), col("name", "Name"), col("category", "Category"), col("designation", "Designation"),
                        col("employmentStatus", "Employment status"), col("employmentType", "Employment type"), col("units", "Units"),
                        col("account", "Sign-in account"), col("joinedOn", "Joined on"), col("assignments", "Current teaching assignments")),
                List.of("dateRange", "orgUnitId", "status"), List.of("name", "staffNo", "joinedOn"), "Joined on", false, List.of("ACTIVE", "ON_LEAVE", "SEPARATED")),
                """
                s.staff_no, concat_ws(' ', s.given_name, s.family_name), initcap(replace(lower(s.category), '_', ' ')), s.designation, s.employment_status,
                s.employment_type, (SELECT string_agg(ou.name, '; ' ORDER BY ou.name) FROM org_assignment oa JOIN org_unit ou ON ou.id = oa.org_unit_id
                WHERE oa.staff_profile_id = s.id), s.user_account_id IS NOT NULL, s.joined_on,
                (SELECT count(*) FROM faculty_assignment f JOIN subject_offering o ON o.id = f.offering_id WHERE f.staff_profile_id = s.id AND f.ended_at IS NULL
                AND o.status IN ('PLANNED', 'ACTIVE'))""",
                "staff_profile s", "true", null, "SELECT oa2.org_unit_id AS unit FROM org_assignment oa2 WHERE oa2.staff_profile_id = s.id",
                Map.of("status", "s.employment_status = ?"), "s.joined_on",
                Map.of("name", "lower(s.given_name || ' ' || coalesce(s.family_name, ''))", "staffNo", "lower(s.staff_no)", "joinedOn", "s.joined_on"),
                (rs, p) -> List.of(nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4)), nz(rs.getString(5)), nz(rs.getString(6)),
                        nz(rs.getString(7)), rs.getBoolean(8) ? "Linked" : "None", str(rs.getObject(9)), String.valueOf(rs.getLong(10))), null, null));

        add(new Spec(new Definition("batches", "Batches and enrolment", "Batches with their course, academic year, capacity and current enrolment.",
                "academics.read", List.of(col("program", "Program"), col("course", "Course"), col("department", "Department"), col("batch", "Batch"),
                        col("batchName", "Name"), col("academicYear", "Academic year"), col("admissionYear", "Admission year"), col("status", "Status"),
                        col("capacity", "Capacity"), col("enrolled", "Current enrolment"), col("offerings", "Subject offerings")),
                List.of("orgUnitId", "academicYearId", "programId", "courseId", "status"), List.of("batch", "program", "enrolled"), null, false,
                List.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED")),
                """
                p.name, c.name, d.name, b.code, b.name, y.name, b.admission_year, b.status, b.capacity,
                (SELECT count(*) FROM enrolment e WHERE e.batch_id = b.id AND e.is_current AND e.status IN ('ACTIVE', 'SUSPENDED')),
                (SELECT count(*) FROM subject_offering o WHERE o.batch_id = b.id AND o.status IN ('PLANNED', 'ACTIVE'))""",
                "batch b JOIN course c ON c.id = b.course_id JOIN program p ON p.id = c.program_id JOIN org_unit d ON d.id = c.department_id "
                        + "JOIN academic_year y ON y.id = b.academic_year_id", "true", "d.path", null,
                Map.of("academicYearId", "b.academic_year_id = ?", "programId", "c.program_id = ?", "courseId", "b.course_id = ?", "status", "b.status = ?"), null,
                Map.of("batch", "lower(b.code)", "program", "lower(p.name)", "enrolled", "10"),
                (rs, p) -> List.of(nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4)), nz(rs.getString(5)), nz(rs.getString(6)),
                        String.valueOf(rs.getInt(7)), nz(rs.getString(8)), rs.getObject(9) == null ? "" : String.valueOf(rs.getInt(9)), enrolledCount(rs.getLong(10)),
                        String.valueOf(rs.getLong(11))), null, null));

        add(new Spec(new Definition("imports", "Student imports", "Bulk import jobs with their outcome and duration.", "student.import",
                List.of(col("importId", "Import ID"), col("file", "File"), col("institution", "Institution"), col("uploadedBy", "Uploaded by"),
                        col("uploadedAt", "Uploaded at"), col("mode", "Mode"), col("status", "Status"), col("total", "Rows"), col("imported", "Imported"),
                        col("updated", "Updated"), col("failed", "Failed"), col("skipped", "Not imported"), col("duration", "Duration (s)")),
                List.of("dateRange", "orgUnitId", "status"), List.of("uploadedAt", "status", "file"), "Uploaded", false,
                List.of("UPLOADED", "QUEUED", "VALIDATING", "READY", "PROCESSING", "COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED")),
                """
                j.id, j.file_name, ou.name, u.display_name, j.created_at, j.mode, j.status, j.total_rows, j.imported_rows, j.updated_rows, j.failed_rows,
                j.skipped_rows, extract(epoch FROM j.finished_at - j.started_at)::bigint""",
                "student_import_job j JOIN org_unit ou ON ou.id = j.institution_id LEFT JOIN user_account u ON u.id = j.created_by", "true", "ou.path", null,
                Map.of("status", "j.status = ?"), "j.created_at::date", Map.of("uploadedAt", "j.created_at", "status", "j.status", "file", "lower(j.file_name)"),
                (rs, p) -> List.of(rs.getString(1), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4)), str(rs.getTimestamp(5).toInstant()),
                        nz(rs.getString(6)), rs.getString(7), String.valueOf(rs.getInt(8)), String.valueOf(rs.getInt(9)), String.valueOf(rs.getInt(10)),
                        String.valueOf(rs.getInt(11)), String.valueOf(rs.getInt(12)), str(rs.getObject(13))), null, null));
    }

    private void add(Spec s) { specs.put(s.def().key(), s); }

    private static Column col(String key, String label) { return new Column(key, label, false); }

    private static Column personal(String key, String label) { return new Column(key, label, true); }

    private String enrolledCount(long n) {
        return scope.any(Permission.STUDENT_READ.key()) ? String.valueOf(n) : "";
    }

    /** Reports the caller may run. */
    @Transactional(readOnly = true)
    public List<Definition> catalogue() {
        requireReports();
        return specs.values().stream().map(Spec::def).filter(d -> scope.any(d.permission())).toList();
    }

    // ================================================================================================ running

    @Transactional
    public Result run(String key, Query q, int page, int size) {
        Spec spec = spec(key);
        boolean personal = personalRequested(spec, q);
        List<Object> params = new ArrayList<>();
        String where = where(spec, q, params);
        if (where == null) return new Result(key, columns(spec, personal), List.of(), page, size, 0, 0, personal);
        long total = jdbc.sql("SELECT count(*) FROM " + spec.from() + where).params(params).query(Long.class).single();
        Map<UUID, Boolean> allowed = new HashMap<>();
        List<Object> p = new ArrayList<>(params);
        p.add(size);
        p.add((long) page * size);
        List<List<String>> rows = jdbc.sql("SELECT " + spec.select() + " FROM " + spec.from() + where + order(spec, q) + " LIMIT ? OFFSET ?").params(p)
                .query((rs, n) -> row(spec, rs, personal, allowed)).list();
        if (personal) {
            audit.recordSuccess("report.personal_viewed", "report", key, filterDetails(q, rows.size()));
        }
        return new Result(key, columns(spec, personal), rows, page, size, total, (int) Math.ceil(total / (double) size), personal);
    }

    /** Streams every matching row (at most 50,000) as CSV or XLSX; audited. Returns the file name. */
    @Transactional
    public String export(String key, Query q, TabularFile.Format format, OutputStream out) {
        Spec spec = spec(key);
        boolean personal = personalRequested(spec, q);
        List<Object> params = new ArrayList<>();
        String where = where(spec, q, params);
        long total = where == null ? 0 : jdbc.sql("SELECT count(*) FROM " + spec.from() + where).params(params).query(Long.class).single();
        if (total > MAX_EXPORT_ROWS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "export_too_large",
                    "The report has " + total + " rows; exports are limited to " + MAX_EXPORT_ROWS + ". Narrow the filters.");
        }
        List<Column> cols = columns(spec, personal);
        int[] count = {0};
        Map<UUID, Boolean> allowed = new HashMap<>();
        try (TabularWriter w = TabularWriter.of(format, out)) {
            w.sheet(spec.def().title(), null);
            w.header(cols.stream().map(Column::label).toList());
            if (where != null) {
                jdbc.sql("SELECT " + spec.select() + " FROM " + spec.from() + where + order(spec, q)).params(params).query(rs -> {
                    try {
                        w.row(row(spec, rs, personal, allowed));
                        count[0]++;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> d = filterDetails(q, count[0]);
        d.put("format", format.name());
        audit.recordSuccess("report.exported", "report", key, d);
        return "ivorovi-" + key + "-" + LocalDate.now() + (format == TabularFile.Format.CSV ? ".csv" : ".xlsx");
    }

    private Spec spec(String key) {
        requireReports();
        Spec s = specs.get(key);
        if (s == null) throw ApiException.notFound("Report");
        if (!scope.any(s.def().permission())) throw new AccessDeniedException("Missing permission " + s.def().permission());
        return s;
    }

    private void requireReports() {
        if (!authz.hasAnywhere(REPORT)) throw new AccessDeniedException("Missing permission " + REPORT);
    }

    private boolean personalRequested(Spec spec, Query q) {
        if (!q.includePersonal()) return false;
        if (spec.personalPermission() == null) throw ApiException.badRequest("personal_not_available", "This report has no personal columns.");
        if (!authz.hasAnywhere(spec.personalPermission())) throw new AccessDeniedException("Missing permission " + spec.personalPermission());
        return true;
    }

    private List<Column> columns(Spec spec, boolean personal) {
        return spec.def().columns().stream().filter(c -> personal || !c.personal()).toList();
    }

    /** Personal values only for records whose unit the viewer may read personal data of; others are masked. */
    private List<String> row(Spec spec, ResultSet rs, boolean personal, Map<UUID, Boolean> allowedByUnit) throws SQLException {
        if (!personal) return spec.mapper().map(rs, false);
        UUID unit = rs.getObject(spec.personalScopeColumn(), UUID.class);
        boolean allowed = allowedByUnit.computeIfAbsent(unit, u -> authz.hasInOrgUnit(spec.personalPermission(), u));
        if (allowed) return spec.mapper().map(rs, true);
        List<String> r = new ArrayList<>(spec.mapper().map(rs, false));
        for (Column c : spec.def().columns()) if (c.personal()) r.add("(not permitted)");
        return r;
    }

    private String where(Spec spec, Query q, List<Object> params) {
        StringBuilder w = new StringBuilder(" WHERE ").append(spec.base());
        if (spec.dateExpr() != null) {
            if (q.from() != null) { w.append(" AND ").append(spec.dateExpr()).append(" >= ?"); params.add(q.from()); }
            if (q.to() != null) { w.append(" AND ").append(spec.dateExpr()).append(" <= ?"); params.add(q.to()); }
        }
        Map<String, Object> values = new HashMap<>();
        values.put("academicYearId", q.academicYearId());
        values.put("programId", q.programId());
        values.put("courseId", q.courseId());
        values.put("batchId", q.batchId());
        if (q.status() != null && !q.status().isBlank()) {
            if (!spec.def().statuses().contains(q.status())) throw ApiException.badRequest("invalid_status", "Unknown status for this report.");
            values.put("status", q.status());
        }
        for (Map.Entry<String, String> f : spec.filters().entrySet()) {
            Object v = values.get(f.getKey());
            if (v != null) { w.append(" AND ").append(f.getValue()); params.add(v); }
        }
        boolean visible = spec.staffScope() != null
                ? scope.appendExists(spec.def().permission(), spec.staffScope(), q.orgUnitId(), w, params)
                : scope.append(spec.def().permission(), spec.scopePath(), q.orgUnitId(), w, params);
        return visible ? w.toString() : null;
    }

    private String order(Spec spec, Query q) {
        String expr = q.sort() == null ? null : spec.sorts().get(q.sort());
        if (expr == null) expr = spec.sorts().values().iterator().next();
        return " ORDER BY " + expr + (q.ascending() ? " ASC" : " DESC") + " NULLS LAST";
    }

    private static Map<String, String> filterDetails(Query q, int rows) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("rows", String.valueOf(rows));
        d.put("personal", String.valueOf(q.includePersonal()));
        if (q.from() != null) d.put("from", q.from().toString());
        if (q.to() != null) d.put("to", q.to().toString());
        if (q.orgUnitId() != null) d.put("orgUnitId", q.orgUnitId().toString());
        if (q.academicYearId() != null) d.put("academicYearId", q.academicYearId().toString());
        if (q.programId() != null) d.put("programId", q.programId().toString());
        if (q.courseId() != null) d.put("courseId", q.courseId().toString());
        if (q.batchId() != null) d.put("batchId", q.batchId().toString());
        if (q.status() != null) d.put("status", q.status());
        return d;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
