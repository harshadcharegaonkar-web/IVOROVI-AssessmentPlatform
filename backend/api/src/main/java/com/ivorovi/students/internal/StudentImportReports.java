package com.ivorovi.students.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.tabular.TabularFile;
import com.ivorovi.platform.tabular.TabularWriter;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.students.internal.StudentImportService.IssueView;
import com.ivorovi.students.internal.StudentImportService.JobRow;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Downloadable import templates and reports (CSV or XLSX, streamed). Reports contain row numbers, column names, codes,
 * messages and student identifiers; only the corrections file repeats the original values (needs
 * {@code student.personal.read}). Every download is audited.
 */
@Component
public class StudentImportReports {

    public enum Kind { VALIDATION, ERRORS, RESULT, CORRECTIONS }

    private final JdbcClient jdbc;
    private final StudentImportService imports;
    private final StudentImportEngine engine;
    private final AuditLog audit;
    private final JsonMapper json;
    private final StudentOptions options;

    StudentImportReports(JdbcClient jdbc, StudentImportService imports, StudentImportEngine engine, AuditLog audit, JsonMapper json, StudentOptions options) {
        this.options = options;
        this.jdbc = jdbc;
        this.imports = imports;
        this.engine = engine;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------------------------------------ templates

    /** Standard template: chosen sections as columns, plus instruction and reference-value sheets (XLSX). */
    /** Not read-only: template downloads are audited. */
    @Transactional
    public void template(UUID institutionId, Set<ImportField.Section> sections, TabularFile.Format format, OutputStream out) {
        imports.requireAnyImporter();
        Set<ImportField.Section> chosen = sections == null || sections.isEmpty() ? EnumSet.allOf(ImportField.Section.class) : sections;
        if (institutionId != null) imports.requireImporter(institutionId);
        final StudentImportEngine.Refs refs = institutionId == null ? null : engine.loadRefs(institutionId);
        List<ImportField> fields = new ArrayList<>();
        for (ImportField f : ImportField.values()) {
            if (!chosen.contains(f.section)) continue;
            if (f.section == ImportField.Section.GUARDIAN && f.name().startsWith("GUARDIAN2")) {
                if (!chosen.contains(ImportField.Section.GUARDIAN)) continue;
            }
            fields.add(f);
        }
        if (!fields.contains(ImportField.FIRST_NAME)) fields.addFirst(ImportField.FIRST_NAME);
        Map<String, Boolean> required = new LinkedHashMap<>();
        for (StudentImportService.FieldView v : imports.fields(institutionId)) required.put(v.key(), v.required());
        write(out, format, w -> {
            w.sheet("Students", fields.stream().map(f -> Math.max(14, Math.min(32, f.label.length() + 4))).toList());
            w.header(fields.stream().map(f -> f.label).toList());
            w.sheet("Instructions", List.of(28, 26, 12, 50, 22, 50));
            w.header(List.of("Column heading", "Section", "Required", "Format and allowed values", "Example", "Notes"));
            List<StudentImportService.FieldView> catalogue = imports.fields(null);
            for (ImportField f : fields) {
                StudentImportService.FieldView v = catalogue.stream().filter(x -> x.key().equals(f.key())).findFirst().orElseThrow();
                w.row(List.of(f.label, f.section.label, Boolean.TRUE.equals(required.get(f.key())) ? "Yes" : v.requiredWhen() == null ? "No" : "Conditional",
                        f.hint == null ? "" : f.hint, f.example == null ? "" : f.example,
                        (v.requiredWhen() == null ? "" : v.requiredWhen() + ". ") + (f.personal ? "Personal information: needs 'View student personal details'." : "")));
            }
            w.row(List.of());
            w.row(List.of("General rules", "", "", "One row per student; the first row holds the column headings. Dates as YYYY-MM-DD or DD/MM/YYYY. "
                    + "Formulas and macros are not accepted (paste values). Format phone and ID columns as text so leading zeros are kept.", "", ""));
            if (format == TabularFile.Format.XLSX) {
                w.sheet("Reference values", List.of(22, 26, 40, 22, 22));
                w.header(List.of("Kind", "Code", "Name", "Belongs to", "Status"));
                for (Map.Entry<String, String> e : Map.of("Female", "FEMALE", "Male", "MALE", "Other", "OTHER", "Undisclosed", "UNDISCLOSED").entrySet()) {
                    w.row(List.of("Gender", e.getKey(), e.getKey(), "", ""));
                }
                for (String r : List.of("Mother", "Father", "Guardian", "Grandparent", "Sibling", "Spouse", "Relative", "Other")) w.row(List.of("Guardian relationship", r, r, "", ""));
                for (String s : List.of("Prospective", "Admitted", "Enrolled", "Active")) w.row(List.of("Student status", s, s, "", ""));
                for (StudentService.Option o : options.all()) {
                    if (o.kind() != StudentService.OptionKind.DOCUMENT_CATEGORY && o.active()) {
                        w.row(List.of(o.kind() == StudentService.OptionKind.ADMISSION_TYPE ? "Admission type" : "Admission category", o.code(), o.label(), "", ""));
                    }
                }
                if (refs != null) {
                    StudentImportEngine.Refs r = refs;
                    for (StudentImportEngine.Refs.Unit d : r.departments) w.row(List.of("Department", d.code(), d.name(), r.institution.name(), ""));
                    for (StudentImportEngine.Refs.Program p : r.programs) w.row(List.of("Program", p.code(), p.name(), "", p.status()));
                    for (StudentImportEngine.Refs.Course c : r.courses) {
                        w.row(List.of("Course", c.code(), c.name(), r.programById(c.programId()).map(StudentImportEngine.Refs.Program::code).orElse(""), c.status()));
                    }
                    for (StudentImportEngine.Refs.Batch b : r.batches) {
                        w.row(List.of("Batch", b.code(), b.name(), r.courseById(b.courseId()).map(StudentImportEngine.Refs.Course::code).orElse("") + " / "
                                + r.yearById(b.yearId()).map(StudentImportEngine.Refs.Year::code).orElse(""), b.status()));
                    }
                    for (StudentImportEngine.Refs.Year y : r.years) w.row(List.of("Academic year", y.code(), y.name(), "", y.status()));
                }
            }
        });
        audit.recordSuccess("student.import.template_downloaded", "student_import", null,
                Map.of("format", format.name(), "sections", String.join(",", chosen.stream().map(Enum::name).sorted().toList())));
    }

    // ------------------------------------------------------------------------------------------------ reports

    /** Report downloads are audited (not read-only). */
    @Transactional
    public String report(UUID jobId, Kind kind, TabularFile.Format format, OutputStream out) {
        JobRow j = imports.visible(jobId, false);
        boolean personal = imports.personalAllowed(j.institutionId());
        if (kind == Kind.CORRECTIONS && !personal) throw new AccessDeniedException("Missing permission " + StudentAccess.PERSONAL);
        if (kind == Kind.CORRECTIONS && j.sourcePurgedAt() != null) {
            throw StudentAccess.conflict("source_purged", "The original values were deleted after the retention period; only the error report is available.");
        }
        if (kind == Kind.RESULT && !EnumSet.of(StudentImportService.JobStatus.COMPLETED, StudentImportService.JobStatus.PARTIALLY_COMPLETED,
                StudentImportService.JobStatus.FAILED, StudentImportService.JobStatus.CANCELLED).contains(j.status())) {
            throw StudentAccess.conflict("import_not_finished", "The result report is available when the import has finished.");
        }
        List<String> headers = json.readValue(j.headersJson(), new TypeReference<List<String>>() {});
        String base = j.fileName().replaceAll("\\.[A-Za-z0-9]+$", "");
        String name = base + "-" + kind.name().toLowerCase() + (format == TabularFile.Format.CSV ? ".csv" : ".xlsx");
        int[] rows = {0};
        write(out, format, w -> {
            switch (kind) {
                case VALIDATION, ERRORS -> {
                    w.sheet(kind == Kind.ERRORS ? "Errors" : "Validation", List.of(8, 24, 22, 10, 16, 60, 60, 26, 16));
                    w.header(List.of("Row", "Column", "Field", "Severity", "Error category", "Error description", "Recommended correction", "Student", "Student ID"));
                    jdbc.sql("""
                            SELECT row_no, issues::text, display_name, student_no, validation_status, match_reason, outcome, outcome_code, outcome_message
                            FROM student_import_row WHERE job_id = ? ORDER BY row_no""").param(jobId).query(rs -> {
                        List<IssueView> issues = json.readValue(rs.getString(2), new TypeReference<List<IssueView>>() {});
                        String rowNo = String.valueOf(rs.getInt(1)), who = nz(rs.getString(3)), sid = nz(rs.getString(4));
                        for (IssueView i : issues) {
                            if (kind == Kind.ERRORS && !"ERROR".equals(i.severity())) continue;
                            row(w, List.of(rowNo, nz(i.column()), nz(i.field()), i.severity(), label(i.category()), i.message(), nz(i.hint()), who, sid));
                            rows[0]++;
                        }
                        String status = rs.getString(5);
                        if (("CONFLICT".equals(status) || "DUPLICATE".equals(status) || "NEEDS_DECISION".equals(status)) && rs.getString(6) != null) {
                            row(w, List.of(rowNo, "", "", "CONFLICT".equals(status) ? "ERROR" : "WARNING", "CONFLICT".equals(status) ? "Conflict" : "Duplicate",
                                    rs.getString(6), "CONFLICT".equals(status) ? "Correct the identifiers or the student's details, or update the existing profile."
                                            : "DUPLICATE".equals(status) ? "No action needed: the row is skipped." : "Decide whether to create a new student or skip the row.", who, sid));
                            rows[0]++;
                        }
                        if ("FAILED".equals(rs.getString(7))) {
                            row(w, List.of(rowNo, "", "", "ERROR", "Import failure", nz(rs.getString(9)), correction(rs.getString(8)), who, sid));
                            rows[0]++;
                        }
                    });
                }
                case RESULT -> {
                    w.sheet("Result", List.of(8, 14, 26, 18, 16, 70));
                    w.header(List.of("Row", "Outcome", "Student", "Student ID", "Code", "Message"));
                    jdbc.sql("SELECT row_no, outcome, display_name, student_no, outcome_code, outcome_message FROM student_import_row WHERE job_id = ? ORDER BY row_no")
                            .param(jobId).query(rs -> {
                                row(w, List.of(String.valueOf(rs.getInt(1)), rs.getString(2), nz(rs.getString(3)), nz(rs.getString(4)), nz(rs.getString(5)), nz(rs.getString(6))));
                                rows[0]++;
                            });
                }
                case CORRECTIONS -> {
                    List<String> h = new ArrayList<>(headers);
                    h.add("Errors (delete this column before re-uploading)");
                    w.sheet("Rows to correct", null);
                    w.header(h);
                    jdbc.sql("""
                            SELECT row_no, cells_enc, issues::text, match_reason, outcome_message FROM student_import_row
                            WHERE job_id = ? AND (validation_status IN ('INVALID', 'CONFLICT') OR outcome = 'FAILED') ORDER BY row_no""").param(jobId).query(rs -> {
                        StudentImportService.Cells cells = imports.decryptCells(rs.getString(2));
                        List<String> values = new ArrayList<>();
                        for (int i = 0; i < headers.size(); i++) values.add(i < cells.c().size() ? cells.c().get(i) : null);
                        List<IssueView> issues = json.readValue(rs.getString(3), new TypeReference<List<IssueView>>() {});
                        List<String> msgs = new ArrayList<>();
                        for (IssueView i : issues) if ("ERROR".equals(i.severity())) msgs.add((i.column() == null ? "" : i.column() + ": ") + i.message());
                        if (rs.getString(4) != null && msgs.isEmpty()) msgs.add(rs.getString(4));
                        if (rs.getString(5) != null && msgs.isEmpty()) msgs.add(rs.getString(5));
                        values.add("Row " + rs.getInt(1) + ": " + String.join(" | ", msgs));
                        row(w, values);
                        rows[0]++;
                    });
                }
                default -> throw ApiException.badRequest("invalid_report", "Unknown report.");
            }
        });
        audit.recordSuccess("student.import.report_downloaded", "student_import", jobId.toString(),
                Map.of("report", kind.name(), "format", format.name(), "lines", String.valueOf(rows[0])));
        return name;
    }

    private static String correction(String code) {
        if (code == null) return "Correct the row and import it again.";
        return switch (code) {
            case "changed_since_validation", "duplicate_identifier", "validation_changed" -> "Upload the file again so it is validated against the current records.";
            case "not_permitted" -> "Ask an administrator with access to this department or to personal details to import the row.";
            case "batch_full" -> "Increase the batch capacity in Academics or choose another batch.";
            case "system_error" -> "Retry the import; contact support if it keeps failing.";
            default -> "Correct the row and import it again.";
        };
    }

    private static String label(String category) {
        if (category == null) return "";
        return switch (category) {
            case "REQUIRED" -> "Missing value";
            case "FORMAT" -> "Format";
            case "INVALID_VALUE" -> "Invalid value";
            case "REFERENCE" -> "Academic reference";
            case "PERMISSION" -> "Permission";
            case "DUPLICATE" -> "Duplicate";
            case "CONFLICT" -> "Conflict";
            case "POLICY" -> "Institution policy";
            default -> category;
        };
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private interface Body {
        void write(TabularWriter w) throws IOException;
    }

    private static void write(OutputStream out, TabularFile.Format format, Body body) {
        try (TabularWriter w = TabularWriter.of(format, out)) {
            body.write(w);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void row(TabularWriter w, List<String> values) {
        try {
            w.row(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
