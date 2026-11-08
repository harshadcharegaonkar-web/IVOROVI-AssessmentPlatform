# ADR-0007: Four-eyes approval framework

* Status: **Implemented in Increment 2.0** (proposed for acceptance with 2.0; condition C-2, decision Q-04)
* Applies to: every step that needs a second person (compensation now; publication of papers and results, merit lists,
  disqualifications and other approvals from 2.2 on)

## Context

Condition C-2 requires that one person can never perform both steps of a four-eyes control. Q-04 decided that the rule
is never bypassed: with a single eligible person a request stays pending until an authorized second approver or an
approved higher-level escalation decides it.

## Decision

1. **One reusable component** (`approvals` module): `approval_request` holds type, subject, a hash of the reviewed
   payload, the permission the approver must hold, the organization unit, requester, status
   (`PENDING → APPROVED | REJECTED | CANCELLED | EXPIRED`), expiry (default 7 days; 2 days for compensation) and the
   decision (decider, time, reason). Business modules plug in an `ApprovalHandler` per type and are called back in the
   deciding transaction (`onApproved`, `onRejected`, `onClosed`).
2. **Separation of duties is enforced three times:**
   * service rule: the requester is refused (`409 four_eyes_required`), and the attempt is audited
     (`approval.self_decision_denied`, written after the transaction so it never needs a second pooled connection);
   * database: `CHECK (decided_by IS NULL OR decided_by <> requested_by)` and a consistency check that only
     APPROVED/REJECTED carry a decider, so even a direct write cannot self-approve;
   * live permission: the approver must hold the required permission at the subject's unit **at decision time**
     (same live evaluation as KI-59); a revoked role cannot approve.
3. **Requesting needs the permission too:** you can only ask for what you could otherwise do.
4. **Integrity of what is approved:** the handler's `unchanged` check refuses approval when the subject changed after
   the request (e.g. a compensation no longer in `PROPOSED`).
5. **No bypass, no auto-approval:** expiry closes a request (it never approves it); single-approver organizations see
   "0 eligible approvers" and can **escalate** (notifies organization-level holders); the requester can cancel.
6. **Notifications** go to eligible approvers (never to the requester) and the decision goes back to the requester.
7. **Audit:** requested, self-decision denied, approved/rejected (with reason), cancelled, expired, escalated.
8. The KI-59 envelope approval keeps its own implementation (same rule, already enforced) and may migrate later
   without behaviour change.

## Consequences

* Tested at service level and against the database (`ApprovalsIT`: self-decision via API, direct UPDATE/INSERT refused
  by the constraint, revoked approver refused, expiry, cancellation, changed subject, escalation, cross-tenant
  invisibility) and end to end (`IncidentWorkflowIT`, exam E2E with two real controllers).
* Every future four-eyes step reuses this component instead of re-implementing the rule.
