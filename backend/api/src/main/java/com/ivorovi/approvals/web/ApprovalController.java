package com.ivorovi.approvals.web;

import com.ivorovi.approvals.ApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Four-eyes approvals: my requests and the requests I may decide. */
@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals")
@Validated
class ApprovalController {

    @Schema(name = "ApprovalDecisionRequest")
    record Decision(@NotNull Boolean approve, @Size(max = 500) String reason, Long version) {}

    private final ApprovalService approvals;

    ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    @Operation(summary = "view=decide: pending requests I may decide (never my own); view=requests: requests I made")
    List<ApprovalService.Request> list(@RequestParam(defaultValue = "decide") @Pattern(regexp = "decide|requests") String view) {
        return approvals.mine(view);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One request (requester or eligible approvers only)")
    ApprovalService.Request get(@PathVariable UUID id) {
        return approvals.get(id);
    }

    @PostMapping("/{id}/decision")
    @Operation(summary = "Approve or reject (not by the requester; approver must hold the required permission now)")
    ApprovalService.Request decide(@PathVariable UUID id, @RequestBody Decision d) {
        return approvals.decide(id, d.approve(), d.reason(), d.version());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel my pending request")
    ApprovalService.Request cancel(@PathVariable UUID id) {
        return approvals.cancel(id);
    }

    @PostMapping("/{id}/escalate")
    @Operation(summary = "Escalate to organization-level approvers (when no eligible approver is available)")
    ApprovalService.Request escalate(@PathVariable UUID id) {
        return approvals.escalate(id);
    }
}
