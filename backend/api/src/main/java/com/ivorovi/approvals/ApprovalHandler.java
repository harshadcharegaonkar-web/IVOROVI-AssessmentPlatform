package com.ivorovi.approvals;

/** Implemented by modules that need four-eyes approval for a request type. Callbacks run in the deciding transaction. */
public interface ApprovalHandler {

    /** Request type handled, e.g. {@code exam.compensation}. */
    String type();

    /** Human-readable name for screens and notifications. */
    String label();

    /** Called when a second person approved; apply the change here (audited by the framework as well). */
    void onApproved(ApprovalService.Request request);

    default void onRejected(ApprovalService.Request request) {}

    /** Called when a pending request was cancelled by its requester or expired without a decision. */
    default void onClosed(ApprovalService.Request request) {}

    /**
     * False when the subject changed since the request (its current payload hash differs); the approval is then refused
     * so nobody approves something other than what they reviewed.
     */
    default boolean unchanged(ApprovalService.Request request) {
        return true;
    }

    /**
     * Extra live eligibility of the deciding person beyond the request's required permission (e.g. holding several
     * permissions at the scope, or not being the subject of the request). Checked at decision time; false refuses the
     * decision (audited).
     */
    default boolean mayDecide(ApprovalService.Request request, java.util.UUID decider) {
        return true;
    }

    /**
     * Thrown by {@link #onApproved} when the approved change can no longer be applied (a live re-check failed). The
     * framework then undoes the handler's work, closes the request as CANCELLED with the reason, audits
     * {@code approval.invalidated} and reports the refusal; nothing is applied.
     */
    final class Invalidated extends RuntimeException {
        public Invalidated(String reason) {
            super(reason, null, false, false);
        }
    }
}
