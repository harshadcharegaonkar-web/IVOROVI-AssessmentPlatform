package com.ivorovi.proctoring.web;

import com.ivorovi.exams.CandidateAttempts;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.proctoring.internal.ProctoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate side of proctoring (add-on B), on the delivery boundary: the notice and consent, browser signals and webcam
 * snapshots of the candidate's own attempt. The attempt is resolved through {@code exams.CandidateAttempts} exactly as
 * every delivery call; anything else answers 404.
 */
@RestController
@RequestMapping("/api/v1/delivery/portals/{key}/attempts/{attemptId}/proctoring")
@Tag(name = "Examination delivery")
@Validated
class CandidateProctoringController {

    @Schema(name = "ProctoringConsentRequest")
    record ConsentRequest(@NotNull Boolean given, @NotBlank @Size(min = 64, max = 64) String consentHash) {}

    @Schema(name = "ProctoringSignalRequest")
    record SignalRequest(@NotBlank @Pattern(regexp = "FOCUS_LOST|TAB_HIDDEN|FULLSCREEN_EXIT|COPY|PASTE|MULTI_DISPLAY|CAMERA_UNAVAILABLE") String kind,
                         Instant at, Integer durationMs) {}

    @Schema(name = "ProctoringSignalBatch")
    record SignalBatch(@NotNull @Size(max = 50) List<@Valid SignalRequest> signals) {}

    record Stored(int stored) {}

    private final CandidateAttempts attempts;
    private final ProctoringService proctoring;

    CandidateProctoringController(CandidateAttempts attempts, ProctoringService proctoring) {
        this.attempts = attempts;
        this.proctoring = proctoring;
    }

    private ProctoringService.CandidateAttempt mine(String key, UUID attemptId) {
        CandidateAttempts.AttemptRef r = attempts.ownedBy(key, attemptId).orElseThrow(() -> ApiException.notFound("Attempt"));
        return new ProctoringService.CandidateAttempt(r.attemptId(), r.scheduleId(), r.enrolmentId(), r.status());
    }

    @GetMapping
    @Operation(summary = "The proctoring level of this attempt, the notice to read (a draft pending legal review) and the recorded decision")
    ProctoringService.CandidateView view(@PathVariable String key, @PathVariable UUID attemptId) {
        return proctoring.candidateView(mine(key, attemptId));
    }

    @PostMapping("/consent")
    @Operation(summary = "Give or decline consent to exactly the notice shown (its hash); recorded once")
    ProctoringService.CandidateView consent(@PathVariable String key, @PathVariable UUID attemptId, @Valid @RequestBody ConsentRequest r) {
        return proctoring.consent(mine(key, attemptId), r.given(), r.consentHash());
    }

    @PostMapping("/signals")
    @Operation(summary = "Browser signals of the running attempt (at most 50 per call; only after consent)")
    Stored signals(@PathVariable String key, @PathVariable UUID attemptId, @Valid @RequestBody SignalBatch r) {
        return new Stored(proctoring.signals(mine(key, attemptId), r.signals().stream()
                .map(s -> new ProctoringService.SignalInput(s.kind(), s.at(), s.durationMs())).toList()));
    }

    @PostMapping(value = "/snapshots", consumes = MediaType.IMAGE_JPEG_VALUE)
    @Operation(summary = "One webcam snapshot (JPEG, at most 200 KB; level SNAPSHOTS, after consent)")
    ProctoringService.SnapshotView snapshot(@PathVariable String key, @PathVariable UUID attemptId, @RequestBody byte[] image,
                                            @RequestHeader(value = "X-Taken-At", required = false) Instant takenAt) {
        return proctoring.snapshot(mine(key, attemptId), image, takenAt);
    }
}
