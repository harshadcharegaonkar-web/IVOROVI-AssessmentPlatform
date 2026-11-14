package com.ivorovi.exams.web;

import com.ivorovi.exams.internal.ResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Examination results: provisional scores, review, independent approval (via /approvals) and publication. */
@RestController
@RequestMapping("/api/v1/exam/results")
@Tag(name = "Examination results")
@Validated
class ResultController {

    @Schema(name = "ResultReviewRequest")
    record ReviewRequest(@Size(max = 2000) String comment) {}

    private final ResultService results;

    ResultController(ResultService results) {
        this.results = results;
    }

    @GetMapping("/{scheduleId}")
    @PreAuthorize("@authz.hasAnywhere('exam.results.read')")
    @Operation(summary = "Attempts and scores of a scheduled examination, and its result reviews")
    ResultService.Results results(@PathVariable UUID scheduleId) {
        return results.results(scheduleId);
    }

    @PostMapping("/{scheduleId}/reviews")
    @PreAuthorize("@authz.hasAnywhere('exam.results.review')")
    @Operation(summary = "Review all provisional results and request their publication from an independent approver")
    ResultService.Results review(@PathVariable UUID scheduleId, @Valid @RequestBody(required = false) ReviewRequest r) {
        return results.review(scheduleId, r == null ? null : r.comment());
    }
}
