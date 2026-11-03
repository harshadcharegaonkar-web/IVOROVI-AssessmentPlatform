package com.ivorovi.academics.web;

import com.ivorovi.academics.internal.CalendarService;
import com.ivorovi.academics.internal.CalendarService.Status;
import com.ivorovi.platform.idempotency.Idempotent;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Academic years and their terms (semesters, trimesters…). Rules live in {@link CalendarService}. */
@RestController
@RequestMapping("/api/v1/academics")
@Tag(name = "Academic calendar")
@Validated
class CalendarController {

    @Schema(name = "AcademicYearRequest")
    record YearRequest(@Size(max = 30) String code, @Size(max = 100) String name, UUID ownerOrgUnitId, LocalDate startDate, LocalDate endDate,
                       Long version) {}

    @Schema(name = "AcademicTermRequest")
    record TermRequest(@Min(1) @Max(12) Integer sequence, @Size(max = 100) String name, LocalDate startDate, LocalDate endDate,
                       LocalDate registrationStart, LocalDate registrationEnd, LocalDate examStart, LocalDate examEnd, Long version) {}

    @Schema(name = "CalendarStatusRequest")
    record StatusRequest(@NotNull Status status, @NotNull Long version, @Size(max = 500) String reason) {}

    private final CalendarService calendar;

    CalendarController(CalendarService calendar) {
        this.calendar = calendar;
    }

    @GetMapping("/academic-years")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Academic years visible to the caller (organization-wide years are visible to every reader)")
    PageResponse<CalendarService.YearView> years(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Status status, @RequestParam(required = false) UUID ownerOrgUnitId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "startDate") @Pattern(regexp = "startDate|name|code|status|updatedAt") String sort,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        return StructureController.page(calendar.years(q, status, ownerOrgUnitId, page, s, sort, "asc".equals(direction)), page, s);
    }

    @PostMapping("/academic-years")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('academic_calendar.manage')")
    @Operation(summary = "Create an academic year for the organization (no owner) or a university/college")
    ResponseEntity<CalendarService.YearDetail> createYear(@Valid @RequestBody YearRequest r) {
        UUID id = calendar.createYear(year(r));
        return ResponseEntity.created(URI.create("/api/v1/academics/academic-years/" + id)).body(calendar.year(id));
    }

    @GetMapping("/academic-years/{id}")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    CalendarService.YearDetail year(@PathVariable UUID id) {
        return calendar.year(id);
    }

    @PatchMapping("/academic-years/{id}")
    @PreAuthorize("@authz.hasAnywhere('academic_calendar.manage')")
    CalendarService.YearDetail updateYear(@PathVariable UUID id, @Valid @RequestBody YearRequest r) {
        calendar.updateYear(id, year(r));
        return calendar.year(id);
    }

    @PostMapping("/academic-years/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('academic_calendar.manage')")
    @Operation(summary = "Planned → active → completed (corrections need a reason)")
    CalendarService.YearDetail yearStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        calendar.setYearStatus(id, r.status(), r.version(), r.reason());
        return calendar.year(id);
    }

    @PostMapping("/academic-years/{id}/terms")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('academic_calendar.manage')")
    @Operation(summary = "Add a term to the year (inside the year, never overlapping another term)")
    ResponseEntity<CalendarService.TermView> createTerm(@PathVariable UUID id, @Valid @RequestBody TermRequest r) {
        UUID termId = calendar.createTerm(id, term(r));
        return ResponseEntity.created(URI.create("/api/v1/academics/academic-terms/" + termId)).body(calendar.term(termId));
    }

    @GetMapping("/academic-terms")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Terms across visible academic years")
    PageResponse<CalendarService.TermView> terms(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID academicYearId, @RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "startDate") @Pattern(regexp = "startDate|name|sequence|status|year") String sort,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        return StructureController.page(calendar.terms(q, academicYearId, status, page, s, sort, "asc".equals(direction)), page, s);
    }

    @GetMapping("/academic-terms/{id}")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    CalendarService.TermView term(@PathVariable UUID id) {
        return calendar.term(id);
    }

    @PatchMapping("/academic-terms/{id}")
    @PreAuthorize("@authz.hasAnywhere('academic_calendar.manage')")
    CalendarService.TermView updateTerm(@PathVariable UUID id, @Valid @RequestBody TermRequest r) {
        calendar.updateTerm(id, term(r));
        return calendar.term(id);
    }

    @PostMapping("/academic-terms/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('academic_calendar.manage')")
    CalendarService.TermView termStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        calendar.setTermStatus(id, r.status(), r.version(), r.reason());
        return calendar.term(id);
    }

    private static CalendarService.YearCommand year(YearRequest r) {
        return new CalendarService.YearCommand(r.code(), r.name(), r.ownerOrgUnitId(), r.startDate(), r.endDate(), r.version());
    }

    private static CalendarService.TermCommand term(TermRequest r) {
        return new CalendarService.TermCommand(r.sequence(), r.name(), r.startDate(), r.endDate(), r.registrationStart(), r.registrationEnd(),
                r.examStart(), r.examEnd(), r.version());
    }
}
