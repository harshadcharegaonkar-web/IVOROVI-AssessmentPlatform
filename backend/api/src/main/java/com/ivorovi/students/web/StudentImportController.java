package com.ivorovi.students.web;

import com.ivorovi.platform.tabular.TabularFile;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.platform.web.PageResponse;
import com.ivorovi.students.internal.ImportField;
import com.ivorovi.students.internal.StudentImportEngine;
import com.ivorovi.students.internal.StudentImportReports;
import com.ivorovi.students.internal.StudentImportService;
import com.ivorovi.students.internal.StudentImportService.ColumnCommand;
import com.ivorovi.students.internal.StudentImportService.JobStatus;
import com.ivorovi.students.internal.StudentImportService.Mode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Student bulk import: upload, column mapping, background validation, duplicate decisions, confirmation, background
 * processing, history, templates, reports, saved mappings and matching rules. Every endpoint needs
 * {@code student.import}; the service checks it (and {@code student.manage}) at the job's institution.
 */
@RestController
@RequestMapping("/api/v1/student-imports")
@Tag(name = "Student imports")
@Validated
class StudentImportController {

    @Schema(name = "StudentImportMappingRequest")
    record MappingRequest(@NotNull @Size(max = 100) List<@Valid ColumnRequest> columns, @NotNull Long version, @Size(max = 100) String saveAsName,
                          @Size(max = 300) String saveAsDescription, Boolean saveForOrganization) {}

    @Schema(name = "StudentImportColumn")
    record ColumnRequest(@Min(0) @Max(99) int column, @Size(max = 40) String field) {}

    @Schema(name = "StudentImportDecisionRequest")
    record DecisionRequest(@Size(max = 10000) List<Integer> rows, StudentImportEngine.MatchCategory category, @NotNull StudentImportEngine.Decision decision,
                           @NotNull Long version) {}

    @Schema(name = "StudentImportConfirmRequest")
    record ConfirmRequest(@NotNull Mode mode, @NotNull Long version, @Min(0) int expectedRows, boolean acknowledged) {}

    @Schema(name = "StudentImportSavedMappingRequest")
    record SavedMappingRequest(UUID institutionId, @NotNull @Size(min = 1, max = 100) String name, @Size(max = 300) String description,
                               @NotNull @Size(min = 1, max = 100) List<StudentImportService.SavedColumn> columns, Long version) {}

    private final StudentImportService imports;
    private final StudentImportReports reports;

    StudentImportController(StudentImportService imports, StudentImportReports reports) {
        this.imports = imports;
        this.reports = reports;
    }

    // ---------------------------------------------------------------------------------------------------- catalogue

    @GetMapping("/overview")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Import dashboard: jobs in progress, awaiting action, recent results and limits")
    StudentImportService.Overview overview() {
        return imports.overview();
    }

    @GetMapping("/fields")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Importable fields with sections, formats and requirements (for an institution when given)")
    List<StudentImportService.FieldView> fields(@RequestParam(required = false) UUID institutionId) {
        return imports.fields(institutionId);
    }

    @GetMapping("/template")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Download a standard import template (XLSX with instructions and reference values, or CSV headings)")
    ResponseEntity<byte[]> template(@RequestParam(required = false) UUID institutionId, @RequestParam(required = false) List<String> sections,
                                    @RequestParam(defaultValue = "XLSX") String format) {
        TabularFile.Format f = format(format);
        Set<ImportField.Section> s = EnumSet.noneOf(ImportField.Section.class);
        if (sections != null) {
            for (String x : sections) {
                try {
                    s.add(ImportField.Section.valueOf(x.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("invalid_sections", "Unknown section " + x + ".");
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reports.template(institutionId, s, f, out);
        return file("ivorovi-student-import-template" + (f == TabularFile.Format.CSV ? ".csv" : ".xlsx"), f, out.toByteArray());
    }

    // ---------------------------------------------------------------------------------------------------- jobs

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Import history (only institutions the caller may import into)")
    PageResponse<StudentImportService.JobSummary> history(@RequestParam(required = false) JobStatus status, @RequestParam(required = false) UUID institutionId,
                                                          @RequestParam(required = false) @Size(max = 100) String q,
                                                          @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        StudentImportService.Page<StudentImportService.JobSummary> p = imports.history(status, institutionId, q, page, size);
        return new PageResponse<>(p.items(), page, size, p.total(), (int) Math.ceil(p.total() / (double) size));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "Upload a CSV or XLSX file (scanned, checked and stored); returns the job with a suggested column mapping. "
            + "Reusing importKey with the same file returns the existing job; with another file it is rejected (409)")
    StudentImportService.JobView upload(@RequestPart("file") MultipartFile file, @RequestParam UUID institutionId,
                                        @RequestParam(required = false) @Size(max = 100) String importKey,
                                        @RequestParam(required = false) UUID mappingTemplateId) throws IOException {
        return imports.upload(institutionId, file.getOriginalFilename(), file.getBytes(), importKey, mappingTemplateId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Import job with mapping, validation counts, progress and allowed actions")
    StudentImportService.JobView job(@PathVariable UUID id) {
        return imports.job(id);
    }

    @GetMapping("/{id}/sample")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "First rows of the file for the mapping preview (personal values masked without student.personal.read; audited)")
    StudentImportService.SampleRows sample(@PathVariable UUID id, @RequestParam(defaultValue = "5") @Min(1) @Max(20) int rows) {
        return imports.sample(id, rows);
    }

    @PutMapping("/{id}/mapping")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Save the column mapping (optionally as a reusable template) and queue validation")
    StudentImportService.JobView mapping(@PathVariable UUID id, @Valid @RequestBody MappingRequest r) {
        List<ColumnCommand> cols = r.columns().stream().map(c -> new ColumnCommand(c.column(), c.field())).toList();
        return imports.saveMapping(id, cols, r.version(), r.saveAsName(), r.saveAsDescription(), Boolean.TRUE.equals(r.saveForOrganization()));
    }

    @GetMapping("/{id}/rows")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Validated rows with issues, duplicate classification, decisions and outcomes (filters: ALL, ISSUES, ERRORS, INVALID, WARNINGS, "
            + "DUPLICATES, DECISIONS, UNRESOLVED, CONFLICTS, VALID, FAILED, IMPORTED, SKIPPED)")
    PageResponse<StudentImportService.RowView> rows(@PathVariable UUID id, @RequestParam(defaultValue = "ALL") @Size(max = 20) String filter,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        StudentImportService.Page<StudentImportService.RowView> p = imports.rows(id, filter, page, size);
        return new PageResponse<>(p.items(), page, size, p.total(), (int) Math.ceil(p.total() / (double) size));
    }

    @PostMapping("/{id}/decisions")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Record decisions for possible duplicates (rows or a whole category): create new, add to existing, or skip. Never merges")
    StudentImportService.JobView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return imports.decide(id, r.rows(), r.category(), r.decision(), r.version());
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Confirm the previewed import and queue processing (expectedRows must equal the rows the preview showed)")
    StudentImportService.JobView confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmRequest r) {
        if (!r.acknowledged()) throw ApiException.badRequest("confirmation_required", "Confirm that you reviewed the preview.");
        return imports.confirm(id, r.mode(), r.version(), r.expectedRows());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Cancel an import (running all-or-nothing imports are rolled back; valid-rows-only imports keep imported rows)")
    StudentImportService.JobView cancel(@PathVariable UUID id) {
        return imports.cancel(id);
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Retry an import that failed because of a system error (completed rows are kept)")
    StudentImportService.JobView retry(@PathVariable UUID id) {
        return imports.retry(id);
    }

    @GetMapping("/{id}/reports/{kind}")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Download a report: VALIDATION, ERRORS, RESULT or CORRECTIONS (rows to fix with their original values; needs student.personal.read)")
    ResponseEntity<byte[]> report(@PathVariable UUID id, @PathVariable String kind, @RequestParam(defaultValue = "CSV") String format) {
        StudentImportReports.Kind k;
        try {
            k = StudentImportReports.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_report", "Unknown report " + kind + ".");
        }
        TabularFile.Format f = format(format);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String name = reports.report(id, k, f, out);
        return file(name, f, out.toByteArray());
    }

    @GetMapping("/{id}/source")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Download the original file (clean malware scan, within the retention period, student.personal.read; audited)")
    ResponseEntity<byte[]> source(@PathVariable UUID id) {
        StudentImportService.Source s = imports.source(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(s.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(s.contentType())).body(s.content());
    }

    // ---------------------------------------------------------------------------------------------------- saved mappings and rules

    @GetMapping("/mappings")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Saved column mappings (organization-wide and of the institution when given)")
    List<StudentImportService.SavedMapping> mappings(@RequestParam(required = false) UUID institutionId) {
        return imports.mappings(institutionId);
    }

    @PostMapping("/mappings")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "Save a reusable column mapping")
    StudentImportService.SavedMapping createMapping(@Valid @RequestBody SavedMappingRequest r) {
        return imports.saveMappingTemplate(null, r.institutionId(), r.name(), r.description(), r.columns(), null);
    }

    @PutMapping("/mappings/{mappingId}")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Rename or change a saved mapping")
    StudentImportService.SavedMapping updateMapping(@PathVariable UUID mappingId, @Valid @RequestBody SavedMappingRequest r) {
        return imports.saveMappingTemplate(mappingId, null, r.name(), r.description(), r.columns(), r.version());
    }

    @DeleteMapping("/mappings/{mappingId}")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a saved mapping")
    void deleteMapping(@PathVariable UUID mappingId) {
        imports.deleteMappingTemplate(mappingId);
    }

    @GetMapping("/settings")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Duplicate-matching rules and required-field policy per university/college")
    List<StudentImportService.SettingsView> settings() {
        return imports.settingsList();
    }

    @PutMapping("/settings/{institutionId}")
    @PreAuthorize("@authz.hasAnywhere('student.import')")
    @Operation(summary = "Change the matching rules of an institution")
    StudentImportService.SettingsView saveSettings(@PathVariable UUID institutionId, @Valid @RequestBody StudentImportService.SettingsCommand c) {
        return imports.saveSettings(institutionId, c);
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static TabularFile.Format format(String f) {
        try {
            return TabularFile.Format.valueOf(f.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_format", "Use CSV or XLSX.");
        }
    }

    private static ResponseEntity<byte[]> file(String name, TabularFile.Format f, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(f == TabularFile.Format.CSV ? MediaType.parseMediaType("text/csv; charset=utf-8")
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
