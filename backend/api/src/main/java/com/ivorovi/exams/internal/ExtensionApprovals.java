package com.ivorovi.exams.internal;

import com.ivorovi.approvals.ApprovalHandler;
import com.ivorovi.approvals.ApprovalService;
import com.ivorovi.platform.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decision R-2, stricter institution policy: on an examination that requires a second person for extra time, a grant by
 * one holder of exam.attempt.extend is applied only when a different holder approves it. The request expires after 30
 * minutes (the candidate's clock is running); it is applied only while the attempt is still running and the new expiry
 * still lies within the examination window (otherwise the approval is refused and nothing changes). The database refuses
 * any unapproved or self-approved grant on such an examination.
 */
@Component
class ExtensionApprovals implements ApprovalHandler {

    private final AttemptOperations operations;
    private final AuditLog audit;

    ExtensionApprovals(AttemptOperations operations, AuditLog audit) {
        this.operations = operations;
        this.audit = audit;
    }

    @Override
    public String type() {
        return AttemptOperations.EXTENSION_APPROVAL;
    }

    @Override
    public String label() {
        return "Extra time for a candidate";
    }

    @Override
    public boolean unchanged(ApprovalService.Request request) {
        return operations.running(request.subjectId());
    }

    /** Never the person who granted it (also refused by the framework and by the database). */
    @Override
    public boolean mayDecide(ApprovalService.Request request, UUID decider) {
        return !decider.equals(request.requestedBy());
    }

    @Override
    public void onApproved(ApprovalService.Request request) {
        operations.applyApproved(request);
    }

    @Override
    public void onRejected(ApprovalService.Request request) {
        closed(request, "rejected");
    }

    @Override
    public void onClosed(ApprovalService.Request request) {
        closed(request, "closed");
    }

    private void closed(ApprovalService.Request request, String how) {
        audit.recordSuccess("exam.attempt.extension_" + how, "exam_attempt", request.subjectId(), Map.of(
                "schedule", String.valueOf(request.payload().get("scheduleId")), "minutes", String.valueOf(request.payload().get("minutes")),
                "approval", request.id().toString()));
    }
}
