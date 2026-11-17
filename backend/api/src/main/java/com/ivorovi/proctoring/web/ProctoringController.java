package com.ivorovi.proctoring.web;

import com.ivorovi.proctoring.internal.ProctoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff side of proctoring (add-on B): a schedule's proctoring level, the flag review queue, decisions and evidence. */
@RestController
@RequestMapping("/api/v1/proctoring")
@Tag(name = "Proctoring")
@Validated
class ProctoringController {

    @Schema(name = "ProctoringSettingRequest")
    record SettingRequest(@NotBlank @Pattern(regexp = "NONE|BASIC|SNAPSHOTS") String level, @Min(60) @Max(600) Integer snapshotIntervalSeconds,
                          @Min(7) @Max(90) Integer retentionDays, Long version) {}

    @Schema(name = "ProctoringDecisionRequest", description = "A person's decision on a flag. WARN needs warningText (shown to the candidate).")
    record DecisionRequest(@NotBlank @Pattern(regexp = "DISMISS|WARN|INCIDENT|TERMINATION_REQUESTED") String decision, @NotBlank @Size(max = 1000) String reason,
                           @Size(max = 300) String warningText) {}

    private final ProctoringService proctoring;

    ProctoringController(ProctoringService proctoring) {
        this.proctoring = proctoring;
    }

    @GetMapping("/schedules/{id}/settings")
    @PreAuthorize("@authz.hasAnywhere('proctoring.configure') or @authz.hasAnywhere('proctoring.review')")
    @Operation(summary = "The proctoring level of a scheduled examination (NONE when never set)")
    ProctoringService.Setting setting(@PathVariable UUID id) {
        return proctoring.setting(id);
    }

    @PutMapping("/schedules/{id}/settings")
    @PreAuthorize("@authz.hasAnywhere('proctoring.configure')")
    @Operation(summary = "Set the proctoring level, snapshot interval and evidence retention (only before the window opens)")
    ProctoringService.Setting configure(@PathVariable UUID id, @Valid @RequestBody SettingRequest r) {
        return proctoring.configure(id, new ProctoringService.SettingInput(r.level(), r.snapshotIntervalSeconds(), r.retentionDays(), r.version()));
    }

    @GetMapping("/schedules/{id}/summary")
    @PreAuthorize("@authz.hasAnywhere('proctoring.configure') or @authz.hasAnywhere('proctoring.review')")
    @Operation(summary = "Counts for the invigilation monitor: open and high-severity flags, decided flags, consents given and declined")
    ProctoringService.Summary summary(@PathVariable UUID id) {
        return proctoring.summary(id);
    }

    @GetMapping("/schedules/{id}/flags")
    @PreAuthorize("@authz.hasAnywhere('proctoring.review')")
    @Operation(summary = "Review queue: flags of the examination, open ones first (high severity first)")
    List<ProctoringService.Flag> flags(@PathVariable UUID id, @RequestParam(required = false) @Pattern(regexp = "OPEN|DECIDED") String status) {
        return proctoring.flags(id, status);
    }

    @GetMapping("/flags/{flagId}")
    @PreAuthorize("@authz.hasAnywhere('proctoring.review')")
    @Operation(summary = "One flag with the attempt's signal timeline and snapshot list (images only through the evidence endpoint)")
    ProctoringService.FlagDetail flag(@PathVariable UUID flagId) {
        return proctoring.flag(flagId);
    }

    @PostMapping("/flags/{flagId}/decisions")
    @PreAuthorize("@authz.hasAnywhere('proctoring.review')")
    @Operation(summary = "Decide a flag: dismiss, warn the candidate, record an incident, or request termination (four-eyes; someone else decides)")
    ProctoringService.FlagDetail decide(@PathVariable UUID flagId, @Valid @RequestBody DecisionRequest r) {
        return proctoring.decide(flagId, new ProctoringService.DecisionInput(r.decision(), r.reason(), r.warningText()));
    }

    @GetMapping(value = "/snapshots/{snapshotId}/image", produces = MediaType.IMAGE_JPEG_VALUE)
    @PreAuthorize("@authz.hasAnywhere('proctoring.evidence.view')")
    @Operation(summary = "A webcam snapshot kept as evidence (every view audited; never cached)")
    ResponseEntity<byte[]> evidence(@PathVariable UUID snapshotId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(proctoring.evidence(snapshotId));
    }
}
