package com.ivorovi.exams.web;

import com.ivorovi.exams.internal.AttemptService;
import com.ivorovi.exams.internal.CandidateService;
import com.ivorovi.exams.internal.ResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate delivery API (served only by the DELIVERY process role; candidate-realm tokens only). The organization comes
 * from the portal key in the path, resolved by the server.
 */
@RestController
@RequestMapping("/api/v1/delivery")
@Tag(name = "Exam delivery (candidates)")
@Validated
class CandidateDeliveryController {

    @Schema(name = "ServerTime")
    record ServerTime(Instant serverTime) {}

    @Schema(name = "CandidateCheckInRequest")
    record CheckIn(@NotBlank @Size(max = 20) String code, Integer clockOffsetMs, Map<String, Object> systemCheck) {}

    @Schema(name = "CompensationCheckInRequest")
    record CompensationCheckIn(Integer clockOffsetMs, Map<String, Object> systemCheck) {}

    @Schema(name = "PresenceRequest")
    record Presence(@NotNull UUID sessionId) {}

    @Schema(name = "PracticeSaveRequest")
    record Save(@NotNull UUID sessionId, long seq, @Size(max = 4096) String payload) {}

    @Schema(name = "AnswerSaveRequest")
    record AnswerSave(@Min(0) long seq, @NotNull Map<String, Object> response) {}

    @Schema(name = "AttemptSubmitRequest")
    record Submit(@Size(max = 100) String submitKey) {}

    private final CandidateService candidates;
    private final AttemptService attempts;
    private final ResultService results;

    CandidateDeliveryController(CandidateService candidates, AttemptService attempts, ResultService results) {
        this.candidates = candidates;
        this.attempts = attempts;
        this.results = results;
    }

    @GetMapping("/portals/{key}/attempts/{attemptId}/result")
    @Operation(summary = "The candidate's own result, available only after independent approval and publication")
    ResultService.CandidateResult result(@PathVariable String key, @PathVariable UUID attemptId) {
        return results.candidateResult(key, attemptId);
    }

    @GetMapping("/portals/{key}/exams")
    @Operation(summary = "The candidate's scheduled examinations and what they can do now (start, resume, check in first, closed)")
    List<AttemptService.ExamView> exams(@PathVariable String key) {
        return attempts.exams(key);
    }

    @PostMapping("/portals/{key}/exams/{scheduleId}/attempt")
    @Operation(summary = "Start or resume the attempt (one per candidate and schedule; requires check-in; deadline fixed at start)")
    AttemptService.AttemptView start(@PathVariable String key, @PathVariable UUID scheduleId) {
        return attempts.start(key, scheduleId);
    }

    @GetMapping("/portals/{key}/attempts/{attemptId}")
    AttemptService.AttemptView attempt(@PathVariable String key, @PathVariable UUID attemptId) {
        return attempts.get(key, attemptId);
    }

    @PutMapping("/portals/{key}/attempts/{attemptId}/answers/{position}")
    @Operation(summary = "Save one answer; acknowledged only once durable; an older or repeated sequence number changes nothing")
    AttemptService.AnswerAck save(@PathVariable String key, @PathVariable UUID attemptId, @PathVariable int position, @Valid @RequestBody AnswerSave r) {
        return attempts.save(key, attemptId, position, r.seq(), r.response());
    }

    @PostMapping("/portals/{key}/attempts/{attemptId}/submit")
    @Operation(summary = "Submit the attempt (idempotent: a retried submission returns the same result)")
    AttemptService.SubmitResult submit(@PathVariable String key, @PathVariable UUID attemptId, @Valid @RequestBody(required = false) Submit r) {
        return attempts.submit(key, attemptId, r == null ? null : r.submitKey());
    }

    @GetMapping("/time")
    @Operation(summary = "Server time (clock-offset check; the server clock is authoritative)")
    ServerTime time() {
        return new ServerTime(Instant.now());
    }

    @GetMapping("/portals/{key}")
    @Operation(summary = "Public portal information shown before sign-in")
    CandidateService.PortalInfo portal(@PathVariable String key) {
        return candidates.portalInfo(key);
    }

    @GetMapping("/portals/{key}/me")
    @Operation(summary = "Link (or self-register) the signed-in candidate and return MFA status, sessions and compensations")
    CandidateService.Me me(@PathVariable String key) {
        return candidates.me(key);
    }

    @PostMapping("/portals/{key}/check-ins")
    @Operation(summary = "Check in to a session with a single-use session code")
    CandidateService.CheckInResult checkIn(@PathVariable String key, @Valid @RequestBody CheckIn r, HttpServletRequest request) {
        return candidates.checkIn(key, r.code(), r.clockOffsetMs(), r.systemCheck(), request.getRemoteAddr());
    }

    @PostMapping("/portals/{key}/compensations/{id}/check-in")
    @Operation(summary = "Use an approved compensation (a new check-in bound to this candidate)")
    CandidateService.CheckInResult compensationCheckIn(@PathVariable String key, @PathVariable UUID id, @Valid @RequestBody CompensationCheckIn r) {
        return candidates.compensationCheckIn(key, id, r.clockOffsetMs(), r.systemCheck());
    }

    @PostMapping("/portals/{key}/presence")
    CandidateService.PresenceAck presence(@PathVariable String key, @Valid @RequestBody Presence r) {
        return candidates.presence(key, r.sessionId());
    }

    @PostMapping("/portals/{key}/saves")
    @Operation(summary = "Practice save, acknowledged once durable (load-test stand-in for answer saves)")
    CandidateService.SaveAck save(@PathVariable String key, @Valid @RequestBody Save r) {
        return candidates.save(key, r.sessionId(), r.seq(), r.payload());
    }
}
