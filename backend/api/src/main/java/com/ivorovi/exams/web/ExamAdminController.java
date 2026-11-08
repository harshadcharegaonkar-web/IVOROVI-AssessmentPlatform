package com.ivorovi.exams.web;

import com.ivorovi.exams.internal.ExamAdminService;
import com.ivorovi.exams.internal.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Staff administration of exam portals, readiness sessions, session codes, candidates and incidents. */
@RestController
@RequestMapping("/api/v1/exam")
@Tag(name = "Exam administration")
@Validated
class ExamAdminController {

    @Schema(name = "CreateExamPortalRequest")
    record CreatePortal(@NotBlank @Size(max = 40) String portalKey, @NotBlank @Size(max = 150) String name,
                        @NotNull @Pattern(regexp = "OFF|OPTIONAL|REQUIRED") String mfaPolicy, boolean selfRegistration) {}

    @Schema(name = "UpdateExamPortalRequest")
    record UpdatePortal(@NotBlank @Size(max = 150) String name, @NotNull @Pattern(regexp = "OFF|OPTIONAL|REQUIRED") String mfaPolicy,
                        boolean selfRegistration, @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status, long version) {}

    @Schema(name = "CreateReadinessSessionRequest")
    record CreateSession(@NotNull UUID portalId, UUID orgUnitId, @NotBlank @Size(max = 150) String name, @NotNull Instant startsAt, @NotNull Instant endsAt) {}

    @Schema(name = "GenerateSessionCodesRequest")
    record GenerateCodes(@Min(1) @Max(500) int count, Instant validFrom, Instant validUntil) {}

    @Schema(name = "CandidateMfaResetRequest")
    record MfaReset(@NotBlank @Size(max = 300) String reason) {}

    @Schema(name = "ReportExamIncidentRequest")
    record ReportIncident(@NotNull Instant startedAt, @NotNull Instant endedAt, @NotBlank @Size(max = 500) String description, UUID sessionId) {}

    @Schema(name = "ProposeCompensationRequest")
    record Propose(@NotNull @Pattern(regexp = "EXTEND_SESSION|NEW_CHECK_IN|NO_ACTION") String action, Integer extensionMinutes,
                   @NotBlank @Size(max = 500) String rationale, long version) {}

    private final ExamAdminService admin;
    private final IncidentService incidents;

    ExamAdminController(ExamAdminService admin, IncidentService incidents) {
        this.admin = admin;
        this.incidents = incidents;
    }

    @GetMapping("/portals")
    List<ExamAdminService.Portal> portals() {
        return admin.portals();
    }

    @PostMapping("/portals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an exam portal on the shared exam domain (organization-wide permission)")
    ExamAdminService.Portal createPortal(@Valid @RequestBody CreatePortal r) {
        return admin.createPortal(r.portalKey(), r.name(), r.mfaPolicy(), r.selfRegistration());
    }

    @PutMapping("/portals/{id}")
    ExamAdminService.Portal updatePortal(@PathVariable UUID id, @Valid @RequestBody UpdatePortal r) {
        return admin.updatePortal(id, r.name(), r.mfaPolicy(), r.selfRegistration(), r.status(), r.version());
    }

    @GetMapping("/sessions")
    List<ExamAdminService.Session> sessions() {
        return admin.sessions();
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    ExamAdminService.Session createSession(@Valid @RequestBody CreateSession r) {
        return admin.createSession(r.portalId(), r.orgUnitId(), r.name(), r.startsAt(), r.endsAt());
    }

    @GetMapping("/sessions/{id}")
    ExamAdminService.Session session(@PathVariable UUID id) {
        return admin.session(id);
    }

    @PostMapping("/sessions/{id}/cancel")
    ExamAdminService.Session cancel(@PathVariable UUID id) {
        return admin.cancelSession(id);
    }

    @PostMapping("/sessions/{id}/codes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Generate single-use session codes; the codes are returned once and stored only as keyed hashes")
    List<ExamAdminService.IssuedCode> generate(@PathVariable UUID id, @Valid @RequestBody GenerateCodes r) {
        return admin.generateCodes(id, r.count(), r.validFrom(), r.validUntil());
    }

    @GetMapping("/sessions/{id}/codes")
    List<ExamAdminService.Code> codes(@PathVariable UUID id) {
        return admin.codes(id);
    }

    @PostMapping("/sessions/{id}/codes/{codeId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id, @PathVariable UUID codeId) {
        admin.revokeCode(id, codeId);
    }

    @GetMapping("/sessions/{id}/check-ins")
    List<ExamAdminService.CheckIn> checkIns(@PathVariable UUID id) {
        return admin.checkIns(id);
    }

    @GetMapping("/candidates")
    List<ExamAdminService.Candidate> candidates() {
        return admin.candidates();
    }

    @PostMapping("/candidates/{id}/suspend")
    ExamAdminService.Candidate suspend(@PathVariable UUID id) {
        return admin.setCandidateStatus(id, false);
    }

    @PostMapping("/candidates/{id}/reactivate")
    ExamAdminService.Candidate reactivate(@PathVariable UUID id) {
        return admin.setCandidateStatus(id, true);
    }

    @PostMapping("/candidates/{id}/mfa-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the candidate's second factors and sign out all sessions (lost device); the candidate is emailed")
    void resetMfa(@PathVariable UUID id, @Valid @RequestBody MfaReset r) {
        admin.resetCandidateMfa(id, r.reason());
    }

    @GetMapping("/incidents")
    List<IncidentService.Incident> incidents() {
        return incidents.list();
    }

    @GetMapping("/incidents/{id}")
    IncidentService.Incident incident(@PathVariable UUID id) {
        return incidents.get(id);
    }

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report an outage observed by the institution; affected candidates are derived from presence and check-ins")
    IncidentService.Incident report(@Valid @RequestBody ReportIncident r) {
        return incidents.report(r.startedAt(), r.endedAt(), r.description(), r.sessionId());
    }

    @PostMapping("/incidents/{id}/compensations")
    @Operation(summary = "Propose compensation; applied only after a second person approves it (four-eyes)")
    IncidentService.Incident propose(@PathVariable UUID id, @Valid @RequestBody Propose r) {
        return incidents.propose(id, r.action(), r.extensionMinutes(), r.rationale(), r.version());
    }
}
