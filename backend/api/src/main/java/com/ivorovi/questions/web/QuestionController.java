package com.ivorovi.questions.web;

import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.internal.AnswerKeyStore;
import com.ivorovi.questions.internal.QuestionService;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Questions: drafting, versions, review and approval workflow, search, history and the answer key. Answer keys are only
 * returned by the dedicated endpoint (never in question or search responses), with {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Questions")
@Validated
class QuestionController {

    @Schema(name = "QuestionDraftRequest")
    record DraftRequest(@NotBlank @Size(max = 40) String type, @NotNull Map<String, Object> content, @NotNull Map<String, Object> body,
                        Map<String, Object> marking, Map<String, Object> answerKey, @Size(max = 20000) String explanation,
                        @DecimalMin("0") @DecimalMax("1000") BigDecimal marks, @DecimalMin("0") @DecimalMax("1000") BigDecimal negativeMarks,
                        UUID subjectId, UUID topicId, UUID subtopicId, UUID difficultyId, @Size(max = 20) List<UUID> outcomeIds,
                        @Size(max = 20) List<@Size(max = 60) String> tags, Map<String, Object> attributes, @Size(max = 20) String language, Long version) {
        QuestionService.DraftInput toInput() {
            return new QuestionService.DraftInput(type, content, body, marking, answerKey, explanation, marks, negativeMarks, subjectId, topicId, subtopicId,
                    difficultyId, outcomeIds, tags, attributes, language, version);
        }
    }

    @Schema(name = "QuestionReasonRequest")
    record ReasonRequest(@Size(max = 1000) String reason) {}

    @Schema(name = "QuestionRequiredReasonRequest")
    record RequiredReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    @Schema(name = "QuestionReviewRequest")
    record ReviewRequest(@NotNull @Pattern(regexp = "REVIEWED|CHANGES_REQUESTED") String decision, Map<String, Object> checklist,
                         @Size(max = 2000) String comment) {}

    @Schema(name = "MarkingPreviewRequest")
    record PreviewRequest(@NotNull @Size(min = 1, max = 50) List<Object> responses) {}

    @Schema(name = "QuestionAnswerKey")
    record AnswerKey(UUID versionId, int versionNo, Map<String, Object> key, String explanation) {}

    private final QuestionService questions;
    private final AnswerKeyStore keys;

    QuestionController(QuestionService questions, AnswerKeyStore keys) {
        this.questions = questions;
        this.keys = keys;
    }

    // ---------------------------------------------------------------------------------------------------- search + create

    @GetMapping("/question-banks/{bankId}/questions")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "Search a bank (full text over statements and options only; answer keys and explanations are never searched)")
    QuestionService.Page search(@PathVariable UUID bankId, @RequestParam(required = false) @Size(max = 200) String q,
                                @RequestParam(required = false) @Pattern(regexp = "DRAFT|IN_REVIEW|REVIEWED|APPROVED|SUPERSEDED|RETIRED") String state,
                                @RequestParam(required = false) @Size(max = 40) String type, @RequestParam(required = false) UUID subjectId,
                                @RequestParam(required = false) UUID topicId, @RequestParam(required = false) UUID subtopicId,
                                @RequestParam(required = false) UUID difficultyId, @RequestParam(required = false) UUID outcomeId,
                                @RequestParam(required = false) @Size(max = 60) String tag, @RequestParam(required = false) @Size(max = 60) String attributeKey,
                                @RequestParam(required = false) @Size(max = 200) String attributeValue,
                                @RequestParam(required = false) @Pattern(regexp = "ACTIVE|RETIRED") String status,
                                @RequestParam(defaultValue = "false") boolean approvedOnly,
                                @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return questions.search(bankId, new QuestionService.Filters(q, state, type, subjectId, topicId, subtopicId, difficultyId, outcomeId, tag, attributeKey,
                attributeValue, status, approvedOnly), page, size);
    }

    @PostMapping("/question-banks/{bankId}/questions")
    @PreAuthorize("@authz.hasAnywhere('question.author')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a draft question (version 1)")
    QuestionService.Detail create(@PathVariable UUID bankId, @Valid @RequestBody DraftRequest r) {
        return questions.create(bankId, r.toInput());
    }

    // ---------------------------------------------------------------------------------------------------- one question

    @GetMapping("/questions/{id}")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "A question with its current and approved versions, reviews, policy and the actions open to the caller (no answer key)")
    QuestionService.Detail get(@PathVariable UUID id) {
        return questions.get(id);
    }

    @PutMapping("/questions/{id}")
    @PreAuthorize("@authz.hasAnywhere('question.author')")
    @Operation(summary = "Edit the open version (author only); editing a version in review returns it to draft and invalidates its reviews")
    QuestionService.Detail update(@PathVariable UUID id, @Valid @RequestBody DraftRequest r) {
        return questions.updateDraft(id, r.toInput());
    }

    @PostMapping("/questions/{id}/versions")
    @PreAuthorize("@authz.hasAnywhere('question.author')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a new draft version from the approved one (the approved version is unchanged)")
    QuestionService.Detail newVersion(@PathVariable UUID id) {
        return questions.newVersion(id);
    }

    @GetMapping("/questions/{id}/versions/{no}")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    QuestionService.Version version(@PathVariable UUID id, @PathVariable @Min(1) int no) {
        return questions.versionOf(id, no);
    }

    @GetMapping("/questions/{id}/versions/{no}/answer-key")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "The answer key and explanation of a version, only to permitted staff (every view and refusal is audited; never cached)")
    ResponseEntity<AnswerKey> answerKey(@PathVariable UUID id, @PathVariable @Min(1) int no) {
        QuestionService.Version v = questions.versionOf(id, no);
        AnswerKeyStore.Plain p = keys.read(v.id(), AnswerKeyStore.Purpose.VIEW)
                .orElseThrow(() -> ApiException.notFound("Answer key"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .body(new AnswerKey(v.id(), v.versionNo(), p.key(), p.explanation()));
    }

    @PostMapping("/questions/{id}/versions/{no}/marking-preview")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "Mark sample responses against a version (needs answer-key access; audited)")
    ResponseEntity<List<QuestionService.MarkingResult>> markingPreview(@PathVariable UUID id, @PathVariable @Min(1) int no, @Valid @RequestBody PreviewRequest r) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(questions.markingPreview(id, no, r.responses()));
    }

    // ---------------------------------------------------------------------------------------------------- workflow

    @PostMapping("/questions/{id}/submit")
    @PreAuthorize("@authz.hasAnywhere('question.author')")
    @Operation(summary = "Submit the draft for review")
    QuestionService.Detail submit(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest r) {
        return questions.submit(id, r == null ? null : r.reason());
    }

    @PostMapping("/questions/{id}/reviews")
    @PreAuthorize("@authz.hasAnywhere('question.review')")
    @Operation(summary = "Record a review of the version in review (authors cannot review their own questions)")
    QuestionService.Detail review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r) {
        return questions.review(id, "REVIEWED".equals(r.decision()), r.checklist(), r.comment());
    }

    @PostMapping("/questions/{id}/request-changes")
    @PreAuthorize("@authz.hasAnywhere('question.approve')")
    @Operation(summary = "Approver returns the version to its author with a reason (reviewers use a CHANGES_REQUESTED review)")
    QuestionService.Detail requestChanges(@PathVariable UUID id, @Valid @RequestBody RequiredReasonRequest r) {
        return questions.requestChanges(id, r.reason());
    }

    @PostMapping("/questions/{id}/approve")
    @PreAuthorize("@authz.hasAnywhere('question.approve')")
    @Operation(summary = "Approve a reviewed version (never the author's own; the bank's policy is enforced by the service and the database)")
    QuestionService.Detail approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest r) {
        return questions.approve(id, r == null ? null : r.reason());
    }

    @PostMapping("/questions/{id}/retire")
    @PreAuthorize("@authz.hasAnywhere('question.approve')")
    @Operation(summary = "Retire a question from future use (versions already used stay intact)")
    QuestionService.Detail retire(@PathVariable UUID id, @Valid @RequestBody RequiredReasonRequest r) {
        return questions.retire(id, r.reason());
    }

    @GetMapping("/questions/{id}/transitions")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    List<QuestionService.Transition> transitions(@PathVariable UUID id) {
        return questions.transitions(id);
    }

    @GetMapping("/questions/{id}/history")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "Audit history of the question (no content or answer key values)")
    List<QuestionService.HistoryEvent> history(@PathVariable UUID id) {
        return questions.history(id);
    }

    @GetMapping("/question-review-queue")
    @PreAuthorize("@authz.hasAnywhere('question.review') or @authz.hasAnywhere('question.approve')")
    @Operation(summary = "Versions waiting for the caller's review or approval (never the caller's own)")
    List<QuestionService.QueueItem> queue(@RequestParam(defaultValue = "review") @Pattern(regexp = "review|approval") String stage) {
        return questions.queue(stage);
    }
}
