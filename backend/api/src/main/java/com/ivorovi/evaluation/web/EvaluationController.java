package com.ivorovi.evaluation.web;

import com.ivorovi.evaluation.internal.EvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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

/** Manual evaluation (M-G, contract C-2): setup, allocation and progress for managers; tasks for evaluators; moderation. */
@RestController
@RequestMapping("/api/v1/evaluation")
@Tag(name = "Evaluation")
@Validated
class EvaluationController {

    @Schema(name = "EvaluationSetupRequest")
    record SetupRequest(@NotNull String mode, BigDecimal tolerancePercent, @NotNull @Size(max = 200) List<UUID> evaluatorIds) {}

    @Schema(name = "ModerationRequest")
    record ModerationRequest(@NotNull BigDecimal awarded, @NotNull @Size(max = 2000) String reason) {}

    private final EvaluationService evaluation;

    EvaluationController(EvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    @GetMapping("/schedules/{scheduleId}/setup")
    @PreAuthorize("@authz.hasAnywhere('evaluation.manage')")
    @Operation(summary = "Evaluation setup of a scheduled examination: mode, moderation tolerance, evaluators, and who may evaluate")
    EvaluationService.Setup setup(@PathVariable UUID scheduleId) {
        return evaluation.setup(scheduleId);
    }

    @PutMapping("/schedules/{scheduleId}/setup")
    @PreAuthorize("@authz.hasAnywhere('evaluation.manage')")
    @Operation(summary = "Set single or double marking, the moderation tolerance and the evaluator pool")
    EvaluationService.Setup saveSetup(@PathVariable UUID scheduleId, @Valid @RequestBody SetupRequest r) {
        return evaluation.saveSetup(scheduleId, r.mode(), r.tolerancePercent(), r.evaluatorIds());
    }

    @PostMapping("/schedules/{scheduleId}/allocate")
    @PreAuthorize("@authz.hasAnywhere('evaluation.manage')")
    @Operation(summary = "Create anonymous scripts for submitted answers awaiting evaluation and allocate them to evaluators")
    EvaluationService.Progress allocate(@PathVariable UUID scheduleId) {
        return evaluation.allocate(scheduleId);
    }

    @GetMapping("/schedules/{scheduleId}/progress")
    @PreAuthorize("@authz.hasAnywhere('evaluation.manage')")
    @Operation(summary = "Evaluation progress: scripts, answers open, in moderation and final; tasks per evaluator")
    EvaluationService.Progress progress(@PathVariable UUID scheduleId) {
        return evaluation.progress(scheduleId);
    }

    @GetMapping("/schedules/{scheduleId}/moderation")
    @PreAuthorize("@authz.hasAnywhere('evaluation.manage')")
    @Operation(summary = "Answers whose two evaluations differ by more than the tolerance")
    List<EvaluationService.ModerationItem> moderation(@PathVariable UUID scheduleId) {
        return evaluation.moderation(scheduleId);
    }

    @PostMapping("/moderation/{itemId}")
    @PreAuthorize("@authz.hasAnywhere('evaluation.manage')")
    @Operation(summary = "Set the final mark of an answer in moderation, with a reason (never by one of its evaluators)")
    ResponseEntity<Void> moderate(@PathVariable UUID itemId, @Valid @RequestBody ModerationRequest r) {
        evaluation.moderate(itemId, r.awarded(), r.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tasks")
    @PreAuthorize("@authz.hasAnywhere('evaluation.write')")
    @Operation(summary = "My evaluation tasks (anonymous scripts)")
    List<EvaluationService.TaskSummary> tasks(@RequestParam(required = false) String status) {
        return evaluation.myTasks(status);
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("@authz.hasAnywhere('evaluation.write')")
    @Operation(summary = "One of my tasks: question, rubric, model answer (audited) and the answer to evaluate")
    EvaluationService.TaskDetail task(@PathVariable UUID taskId) {
        return evaluation.detail(taskId);
    }

    @PostMapping("/tasks/{taskId}/submission")
    @PreAuthorize("@authz.hasAnywhere('evaluation.write')")
    @Operation(summary = "Submit my marks for a task (per rubric criterion, or one mark without a rubric); final")
    EvaluationService.TaskDetail submit(@PathVariable UUID taskId, @Valid @RequestBody EvaluationService.Submission s) {
        return evaluation.submit(taskId, s);
    }
}
