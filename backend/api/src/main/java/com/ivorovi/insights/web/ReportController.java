package com.ivorovi.insights.web;

import com.ivorovi.insights.internal.ReportService;
import com.ivorovi.platform.tabular.TabularFile;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational reports with filters, server-side pagination and CSV/XLSX export. Needs {@code report.read} and the read
 * permission of the report's records; rows are scoped like the record lists. Personal columns only on request by holders
 * of the personal-data permission (audited); every export is audited.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
@Validated
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "Reports the caller may run, with their columns, filters and sort keys")
    List<ReportService.Definition> catalogue() {
        return reports.catalogue();
    }

    @GetMapping("/{key}")
    @Operation(summary = "Run a report (one page)")
    ReportService.Result run(@PathVariable @Size(max = 40) String key,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID academicYearId,
                             @RequestParam(required = false) UUID programId, @RequestParam(required = false) UUID courseId,
                             @RequestParam(required = false) UUID batchId, @RequestParam(required = false) @Size(max = 40) String status,
                             @RequestParam(defaultValue = "false") boolean includePersonal, @RequestParam(required = false) @Size(max = 40) String sort,
                             @RequestParam(defaultValue = "asc") String direction,
                             @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return reports.run(key, query(from, to, orgUnitId, academicYearId, programId, courseId, batchId, status, includePersonal, sort, direction), page, size);
    }

    @GetMapping("/{key}/export")
    @Operation(summary = "Export every matching row (at most 50,000) as CSV or XLSX; audited")
    ResponseEntity<byte[]> export(@PathVariable @Size(max = 40) String key,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID academicYearId,
                                  @RequestParam(required = false) UUID programId, @RequestParam(required = false) UUID courseId,
                                  @RequestParam(required = false) UUID batchId, @RequestParam(required = false) @Size(max = 40) String status,
                                  @RequestParam(defaultValue = "false") boolean includePersonal, @RequestParam(required = false) @Size(max = 40) String sort,
                                  @RequestParam(defaultValue = "asc") String direction, @RequestParam(defaultValue = "CSV") String format) {
        TabularFile.Format f;
        try {
            f = TabularFile.Format.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_format", "Use CSV or XLSX.");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String name = reports.export(key, query(from, to, orgUnitId, academicYearId, programId, courseId, batchId, status, includePersonal, sort, direction), f, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(f == TabularFile.Format.CSV ? MediaType.parseMediaType("text/csv; charset=utf-8")
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
    }

    private static ReportService.Query query(LocalDate from, LocalDate to, UUID orgUnitId, UUID academicYearId, UUID programId, UUID courseId, UUID batchId,
                                             String status, boolean includePersonal, String sort, String direction) {
        if (from != null && to != null && to.isBefore(from)) throw ApiException.badRequest("invalid_date_range", "The end date is before the start date.");
        return new ReportService.Query(from, to, orgUnitId, academicYearId, programId, courseId, batchId, status, includePersonal, sort,
                !"desc".equalsIgnoreCase(direction));
    }
}
