package com.ivorovi.assessments.web;

import com.ivorovi.assessments.internal.AssessmentService;
import com.ivorovi.assessments.internal.PaperService;
import com.ivorovi.questions.ApprovedQuestions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assessment authoring (Increment 2.2 / M1): definitions, fixed questions and random rules over approved question
 * versions, validation, independent review, independent publication (assessment.publish), immutable published versions,
 * and generated papers. Unit-level permissions are re-checked in the service for the assessment's institution.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Assessments")
@Validated
class AssessmentController {

    private static final String ANY_AUTHOR = "@authz.hasAnywhere('assessment.author') or @authz.hasAnywhere('assessment.author.internal')";
    private static final String ANY_PARTICIPANT = ANY_AUTHOR + " or @authz.hasAnywhere('assessment.review') or @authz.hasAnywhere('assessment.publish')";

    @Schema(name = "AssessmentCreateRequest")
    record CreateRequest(UUID orgUnitId, @NotBlank @Size(max = 40) String code,
                         @NotBlank @Pattern(regexp = "ADMISSION|SEMESTER|PLACEMENT|APTITUDE|INTERNAL|PRACTICE") String purpose,
                         @NotBlank @Size(max = 200) String title, @Size(max = 2000) String description, @Min(1) @Max(600) Integer durationMinutes) {}

    @Schema(name = "AssessmentItemRequest")
    record ItemRequest(@NotNull UUID questionVersionId, @DecimalMin("0") @DecimalMax("1000") BigDecimal marks,
                       @DecimalMin("0") @DecimalMax("1000") BigDecimal negativeMarks) {}

    @Schema(name = "AssessmentRuleRequest")
    record RuleRequest(@Size(max = 150) String label, @NotNull UUID bankId, UUID subjectId, UUID topicId, UUID difficultyId, @Size(max = 40) String questionType,
                       @Min(1) @Max(500) int count, @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal marks,
                       @DecimalMin("0") @DecimalMax("1000") BigDecimal negativeMarks) {}

    @Schema(name = "AssessmentDraftRequest")
    record DraftRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description, @Size(max = 10000) String instructions,
                        @Min(1) @Max(600) Integer durationMinutes, @DecimalMin("0") @DecimalMax("100000") BigDecimal declaredTotalMarks,
                        @Pattern(regexp = "OFF|QUESTION|CUSTOM") String negativeMarking, Boolean shuffleQuestions, Boolean shuffleOptions,
                        @Size(max = 500) List<@Valid ItemRequest> items, @Size(max = 50) List<@Valid RuleRequest> rules, @NotNull Long version) {}

    @Schema(name = "AssessmentReasonRequest")
    record ReasonRequest(@Size(max = 500) String reason) {}

    @Schema(name = "AssessmentReviewRequest")
    record ReviewRequest(@NotNull @Pattern(regexp = "REVIEWED|CHANGES_REQUESTED") String decision, @Size(max = 2000) String comment) {}

    @Schema(name = "AssessmentPoolSize")
    record PoolSize(int size) {}

    private final AssessmentService assessments;
    private final PaperService papers;
    private final ApprovedQuestions questions;

    AssessmentController(AssessmentService assessments, PaperService papers, ApprovedQuestions questions) {
        this.assessments = assessments;
        this.papers = papers;
        this.questions = questions;
    }

    // ================================================================================================ definitions

    @GetMapping("/assessments")
    @PreAuthorize("@authz.hasAnywhere('assessment.read')")
    @Operation(summary = "Assessments the caller may read (newest first)")
    List<AssessmentService.Summary> list(@RequestParam(required = false) String purpose, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) @Size(max = 100) String q) {
        return assessments.list(blank(purpose), blank(state), blank(q));
    }

    @PostMapping("/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ANY_AUTHOR)
    @Operation(summary = "Create an assessment with an empty draft version 1")
    AssessmentService.Detail create(@Valid @RequestBody CreateRequest r) {
        return assessments.create(new AssessmentService.CreateInput(r.orgUnitId(), r.code(), r.purpose(), r.title(), r.description(), r.durationMinutes()));
    }

    @GetMapping("/assessments/{id}")
    @PreAuthorize("@authz.hasAnywhere('assessment.read')")
    AssessmentService.Detail get(@PathVariable UUID id) {
        return assessments.get(id);
    }

    @PutMapping("/assessments/{id}/draft")
    @PreAuthorize(ANY_AUTHOR)
    @Operation(summary = "Replace the open version's definition (a version in review returns to draft)")
    AssessmentService.Detail update(@PathVariable UUID id, @Valid @RequestBody DraftRequest r) {
        List<AssessmentService.ItemInput> items = r.items() == null ? List.of()
                : r.items().stream().map(i -> new AssessmentService.ItemInput(i.questionVersionId(), i.marks(), i.negativeMarks())).toList();
        List<AssessmentService.RuleInput> rules = r.rules() == null ? List.of()
                : r.rules().stream().map(x -> new AssessmentService.RuleInput(x.label(), x.bankId(), x.subjectId(), x.topicId(), x.difficultyId(), x.questionType(),
                        x.count(), x.marks(), x.negativeMarks())).toList();
        return assessments.update(id, new AssessmentService.DraftInput(r.title(), r.description(), r.instructions(), r.durationMinutes(), r.declaredTotalMarks(),
                r.negativeMarking(), r.shuffleQuestions(), r.shuffleOptions(), items, rules, r.version()));
    }

    @GetMapping("/assessments/{id}/validation")
    @PreAuthorize("@authz.hasAnywhere('assessment.read')")
    @Operation(summary = "Validate the open version (errors block submission and publication; warnings do not)")
    AssessmentService.Validation validate(@PathVariable UUID id) {
        return assessments.validate(id);
    }

    @PostMapping("/assessments/{id}/submit")
    @PreAuthorize(ANY_AUTHOR)
    AssessmentService.Detail submit(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest r) {
        return assessments.submit(id, r == null ? null : r.reason());
    }

    @PostMapping("/assessments/{id}/reviews")
    @PreAuthorize("@authz.hasAnywhere('assessment.review')")
    @Operation(summary = "Review the version in review (never by its author); changes requested return it to draft")
    AssessmentService.Detail review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r) {
        return assessments.review(id, "REVIEWED".equals(r.decision()), r.comment());
    }

    @PostMapping("/assessments/{id}/publish")
    @PreAuthorize("@authz.hasAnywhere('assessment.publish')")
    @Operation(summary = "Approve and publish (independent publisher; high-stakes: publisher is not a reviewer)")
    AssessmentService.Detail publish(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest r) {
        return assessments.publish(id, r == null ? null : r.reason());
    }

    @PostMapping("/assessments/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ANY_AUTHOR)
    @Operation(summary = "New draft version from the published one")
    AssessmentService.Detail newVersion(@PathVariable UUID id) {
        return assessments.newVersion(id);
    }

    @GetMapping("/assessments/{id}/versions/{no}")
    @PreAuthorize("@authz.hasAnywhere('assessment.read')")
    AssessmentService.Version version(@PathVariable UUID id, @PathVariable @Min(1) int no) {
        return assessments.versionOf(id, no);
    }

    @GetMapping("/assessments/{id}/transitions")
    @PreAuthorize("@authz.hasAnywhere('assessment.read')")
    List<AssessmentService.Transition> transitions(@PathVariable UUID id) {
        return assessments.transitions(id);
    }

    @GetMapping("/assessments/{id}/history")
    @PreAuthorize("@authz.hasAnywhere('assessment.read')")
    List<AssessmentService.HistoryEvent> history(@PathVariable UUID id) {
        return assessments.history(id);
    }

    @GetMapping("/assessment-queue")
    @PreAuthorize("@authz.hasAnywhere('assessment.review') or @authz.hasAnywhere('assessment.publish')")
    @Operation(summary = "Versions awaiting the caller's review or publication")
    List<AssessmentService.QueueItem> queue(@RequestParam(defaultValue = "review") @Pattern(regexp = "review|publication") String stage) {
        return assessments.queue(stage);
    }

    // ================================================================================================ question picker

    @GetMapping("/assessment-sources/banks")
    @PreAuthorize(ANY_AUTHOR)
    @Operation(summary = "Question banks the caller may read (assessment sources)")
    List<ApprovedQuestions.Bank> banks() {
        return questions.readableBanks();
    }

    @GetMapping("/assessment-sources/banks/{bankId}/questions")
    @PreAuthorize(ANY_AUTHOR)
    @Operation(summary = "Approved question versions of a bank (candidate projection; no answer information)")
    ApprovedQuestions.Page pick(@PathVariable UUID bankId, @RequestParam(required = false) @Size(max = 200) String q, @RequestParam(required = false) UUID subjectId,
                                @RequestParam(required = false) UUID topicId, @RequestParam(required = false) UUID difficultyId,
                                @RequestParam(required = false) @Size(max = 40) String type, @RequestParam(defaultValue = "0") @Min(0) int page,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return questions.search(bankId, blank(q), subjectId, topicId, difficultyId, blank(type), page, size);
    }

    @GetMapping("/assessment-sources/banks/{bankId}/pool-size")
    @PreAuthorize(ANY_AUTHOR)
    @Operation(summary = "Number of approved questions a random rule with these filters would draw from")
    PoolSize poolSize(@PathVariable UUID bankId, @RequestParam(required = false) UUID subjectId, @RequestParam(required = false) UUID topicId,
                      @RequestParam(required = false) UUID difficultyId, @RequestParam(required = false) @Size(max = 40) String type) {
        return new PoolSize(questions.pool(bankId, subjectId, topicId, difficultyId, blank(type)).size());
    }

    // ================================================================================================ papers

    @PostMapping("/assessments/{id}/preview-papers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ANY_PARTICIPANT)
    @Operation(summary = "Generate and store a preview paper (secure seed; candidate projection; no answer information)")
    PaperService.PaperView preview(@PathVariable UUID id, @RequestParam(required = false) @Min(1) Integer versionNo) {
        return papers.preview(id, versionNo);
    }

    @GetMapping("/assessments/{id}/preview-papers")
    @PreAuthorize(ANY_PARTICIPANT)
    List<PaperService.PaperSummary> papers(@PathVariable UUID id) {
        return papers.list(id);
    }

    @GetMapping("/assessment-papers/{paperId}")
    @PreAuthorize(ANY_PARTICIPANT)
    PaperService.PaperView paper(@PathVariable UUID paperId) {
        return papers.get(paperId);
    }

    @PostMapping("/assessment-papers/{paperId}/reproduce")
    @PreAuthorize(ANY_PARTICIPANT)
    @Operation(summary = "Regenerate the paper from its stored seed and compare hashes")
    PaperService.Reproduction reproduce(@PathVariable UUID paperId) {
        return papers.reproduce(paperId);
    }

    @PostMapping("/assessment-papers/{paperId}/seed-disclosures")
    @PreAuthorize("@authz.hasAnywhere('assessment.publish')")
    @Operation(summary = "Disclose a paper's seed for independent verification (assessment.publish; audited)")
    PaperService.Seed seed(@PathVariable UUID paperId) {
        return papers.seed(paperId);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
