package com.ivorovi.scheduling.web;

import com.ivorovi.assessments.PublishedAssessments;
import com.ivorovi.exams.ExamSessions;
import com.ivorovi.scheduling.internal.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Examination scheduling and candidate enrolment (M2). Unit-level permissions are re-checked in the service. */
@RestController
@RequestMapping("/api/v1/exam/schedules")
@Tag(name = "Examination scheduling")
@Validated
class ScheduleController {

    @Schema(name = "ExamScheduleRequest")
    record CreateRequest(@NotNull UUID versionId, @NotNull UUID portalId, UUID orgUnitId, @Size(max = 200) String title, @NotNull Instant opensAt,
                         @NotNull Instant closesAt, Instant lateEntryUntil, @Size(max = 4000) String instructions, Boolean calculator) {}

    @Schema(name = "ExamScheduleCancelRequest")
    record CancelRequest(@NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "EnrolmentUploadRequest")
    record UploadRequest(@NotBlank @Size(max = 600_000) String csv, boolean apply) {}

    @Schema(name = "ExtraTimeRequest")
    record ExtraTimeRequest(@Min(0) @Max(600) int minutes, @Size(max = 500) String reason, long version) {}

    @Schema(name = "ScheduleCodesRequest")
    record CodesRequest(@Min(1) @Max(2000) int count) {}

    @Schema(name = "SchedulablePortal")
    record PortalOption(UUID id, String portalKey, String name, String url) {}

    private final ScheduleService schedules;
    private final PublishedAssessments assessments;
    private final ExamSessions sessions;

    ScheduleController(ScheduleService schedules, PublishedAssessments assessments, ExamSessions sessions) {
        this.schedules = schedules;
        this.assessments = assessments;
        this.sessions = sessions;
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.read')")
    List<ScheduleService.Schedule> list() {
        return schedules.list();
    }

    @GetMapping("/sources")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage')")
    @Operation(summary = "Published assessment versions the caller may schedule")
    List<PublishedAssessments.Published> sources() {
        return assessments.publishedVersions();
    }

    @GetMapping("/portals")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage') and @authz.hasAnywhere('exam.session.manage')")
    List<PortalOption> portals() {
        return sessions.portals().stream().filter(p -> "ACTIVE".equals(p.status())).map(p -> new PortalOption(p.id(), p.portalKey(), p.name(), p.url())).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage')")
    @Operation(summary = "Schedule a published assessment version (creates its exam session; the version never changes)")
    ScheduleService.Schedule create(@Valid @RequestBody CreateRequest r) {
        return schedules.create(new ScheduleService.ScheduleInput(r.versionId(), r.portalId(), r.orgUnitId(), r.title(), r.opensAt(), r.closesAt(),
                r.lateEntryUntil(), r.instructions(), r.calculator()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.read')")
    ScheduleService.Schedule get(@PathVariable UUID id) {
        return schedules.get(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage')")
    ScheduleService.Schedule cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        return schedules.cancel(id, r.reason());
    }

    @GetMapping("/{id}/enrolments")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.read')")
    List<ScheduleService.Enrolment> enrolments(@PathVariable UUID id) {
        return schedules.enrolments(id);
    }

    @PostMapping("/{id}/enrolments/upload")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage')")
    @Operation(summary = "Bulk enrolment from CSV (email,name,extra_minutes,extra_reason); apply=false previews without storing")
    ScheduleService.UploadResult upload(@PathVariable UUID id, @Valid @RequestBody UploadRequest r) {
        return schedules.upload(id, r.csv(), r.apply());
    }

    @PutMapping("/{id}/enrolments/{enrolmentId}/extra-time")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage')")
    ScheduleService.Enrolment extraTime(@PathVariable UUID id, @PathVariable UUID enrolmentId, @Valid @RequestBody ExtraTimeRequest r) {
        return schedules.setExtraTime(id, enrolmentId, r.minutes(), r.reason(), r.version());
    }

    @PostMapping("/{id}/enrolments/{enrolmentId}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage')")
    void withdraw(@PathVariable UUID id, @PathVariable UUID enrolmentId) {
        schedules.withdraw(id, enrolmentId);
    }

    @PostMapping("/{id}/invitations")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage')")
    @Operation(summary = "Email every enrolled candidate not yet invited (or whose earlier email failed)")
    ScheduleService.InviteResult invite(@PathVariable UUID id) {
        return schedules.invite(id);
    }

    @PostMapping("/{id}/codes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.manage') and @authz.hasAnywhere('exam.session.manage')")
    @Operation(summary = "Issue single-use session codes (shown once)")
    List<ExamSessions.IssuedCode> codes(@PathVariable UUID id, @Valid @RequestBody CodesRequest r) {
        return schedules.issueCodes(id, r.count());
    }

    @GetMapping("/{id}/codes")
    @PreAuthorize("@authz.hasAnywhere('exam.schedule.read') and @authz.hasAnywhere('exam.session.manage')")
    List<ExamSessions.Code> listCodes(@PathVariable UUID id) {
        return schedules.codes(id);
    }
}
