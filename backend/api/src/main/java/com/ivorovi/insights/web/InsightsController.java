package com.ivorovi.insights.web;

import com.ivorovi.insights.internal.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboards. Every figure is limited to the units where the caller holds the underlying read permission; sections the
 * caller may not see are omitted ({@code null}). An optional {@code orgUnitId} narrows a dashboard to a subtree.
 */
@RestController
@RequestMapping("/api/v1/insights")
@Tag(name = "Dashboards")
class InsightsController {

    private final DashboardService dashboards;

    InsightsController(DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping("/executive")
    @Operation(summary = "Organization-wide key figures (organization, academics, students, staff, people, imports), each permission-scoped")
    DashboardService.Executive executive() {
        return dashboards.executive();
    }

    @GetMapping("/academic")
    @Operation(summary = "Program, course, subject and batch distributions, enrolment by academic year and semester, faculty assignment overview (academics.read)")
    DashboardService.Academic academic(@RequestParam(required = false) UUID orgUnitId) {
        return dashboards.academic(orgUnitId);
    }

    @GetMapping("/students")
    @Operation(summary = "Student totals, lifecycle states, admission/enrolment/progression trends and distributions (student.read)")
    DashboardService.Students students(@RequestParam(required = false) UUID orgUnitId) {
        return dashboards.students(orgUnitId);
    }

    @GetMapping("/staff")
    @Operation(summary = "Staff totals, distribution by category, department, campus and employment type, faculty assignment summary (staff.read)")
    DashboardService.Staff staff(@RequestParam(required = false) UUID orgUnitId) {
        return dashboards.staff(orgUnitId);
    }

    @GetMapping("/imports")
    @Operation(summary = "Import operations: outcomes, records processed and rejected, durations and recent jobs (student.import)")
    DashboardService.Imports imports(@RequestParam(required = false) UUID orgUnitId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.imports(orgUnitId, from, to);
    }
}
