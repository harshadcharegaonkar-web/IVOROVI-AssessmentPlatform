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
}
