/**
 * Four-eyes approvals (increment 2.0-A): a reusable request/decision workflow where the requester can never be the
 * approver. Business modules register an {@link com.ivorovi.approvals.ApprovalHandler} per request type and are called
 * back, in the deciding transaction, when a request is approved or rejected.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Approvals")
package com.ivorovi.approvals;
