# Runbook — privileged role assignment and bootstrap (P2-08)

**Privileged permissions:** `question.approve` and `question.key.read`. Any role that contains one of them (EXAM_CONTROLLER,
or a custom role) is privileged. Administrators do not hold these permissions and cannot grant them on their own authority.

## Normal path: four-eyes approval (no operator involved)

1. An administrator assigns the role (Users → user → Roles, or `POST /api/v1/users/{id}/role-assignments`).
   - They need `role.assign` and every **non-privileged** permission of the role at the scope (the 1.3 ceiling).
   - The response is `202` with `status: PENDING_APPROVAL` and `approvalRequestId`. Nothing is assigned yet.
   - Audit: `approval.requested`, `role.privileged_assignment_requested`.
2. An existing holder of **all** the role's privileged permissions, at the assignment's unit or above, approves it
   under Approvals (`POST /api/v1/approvals/{id}/decision`).
   - The approver can never be the requester or the person being elevated.
   - Refused decisions are audited as `approval.self_decision_denied` or `approval.decision_denied`.
3. On approval, the same transaction re-checks everything with live SQL and locks both people's assignment rows:
   - the approver's permissions and the requester's authority;
   - that the role is unchanged and the account is active.
4. It then creates the assignment, with the approval id stored on it.
   - Audit: `approval.approved`, `role.assigned`, `role.privileged_assignment_activated`.
5. If any re-check fails, the request becomes **CANCELLED** ("invalidated at approval: …") and nothing is assigned.
   - Audit: `approval.invalidated`.
   - The administrator can ask again.
   - Rejections and expiry (7 days) are audited as `approval.rejected` and `approval.expired`.

**Database backstop:** the trigger `role_assignment_privileged_guard` refuses any privileged assignment written by the
application database role unless it references a matching, unused, APPROVED request. The request's approver must
currently hold the permissions at the scope, and its requester must hold `role.assign` there. The trigger also refuses
re-pointing assignments. `role_privilege_gain_guard` refuses adding a privileged permission to a role in use.

**Revocation:** administrators can revoke a privileged role. Revoking needs `role.assign` and the non-privileged ceiling,
because revocation never grants anything. Audit: `role.revoked`.

## Bootstrap: the first holder (operator procedure)

Use the bootstrap only when the normal path has no eligible approver. That is the case when **nobody** holds the
privileged permissions at or above the scope. Both the command and the database trigger refuse otherwise. There is no
self-approval exception for administrators.

**Who:** a platform operator with production database-operations access (schema owner credentials), acting on a support
ticket.

**Ticket must record:**
- the organization;
- the requesting organization administrator (name and account email);
- the person to elevate (account email; the account must be ACTIVE);
- the role code (for example `EXAM_CONTROLLER`);
- the scope (organization-wide, or a unit id);
- the business reason.

The ticket should be confirmed by a second operator or a customer-success lead before execution.

**Run** (one-shot; the process exits with 0 on success and 2 on refusal):

```bash
IVOROVI_PROCESS_ROLE=ADMIN java -jar ivorovi-api.jar --spring.main.web-application-type=none \
  --ivorovi.ops.bootstrap-privileged-role.enabled=true \
  --ivorovi.ops.bootstrap-privileged-role.tenant-id=<organization id> \
  --ivorovi.ops.bootstrap-privileged-role.user-email=<account email> \
  --ivorovi.ops.bootstrap-privileged-role.role-code=EXAM_CONTROLLER \
  --ivorovi.ops.bootstrap-privileged-role.org-unit-id=<unit id, omit for the whole organization> \
  --ivorovi.ops.bootstrap-privileged-role.operator=<operator name> \
  --ivorovi.ops.bootstrap-privileged-role.ticket=<ticket reference> \
  --ivorovi.ops.bootstrap-privileged-role.reason="<reason>"
```

The usual environment (database URL, owner and application credentials, data key) must be set, as for a normal start.
Credentials are never logged.

**What it does:**
- Validates the organization, the account (ACTIVE), the role (active, contains a privileged permission) and the unit.
- Refuses if anyone already holds a privileged permission of the role at or above the scope.
- Inserts the assignment through the schema-owner connection with `bootstrap_reference = <ticket>`.
- Then, in the organization, records the audit event `role.privileged_bootstrap` with:
  - actor `operator:<name>`;
  - the ticket, reason, target user, role, scope and privileged permissions.
- Emits a `role.assigned` outbox event.

**Audit requirements:**
- The ticket reference is attached to the assignment row and to the audit event.
- Organization administrators can see the event in the audit log.
- Every further privileged assignment in that scope goes through approval.
- Review bootstrap events monthly. Any bootstrap without a matching ticket is a security incident.

**Verification after running:** the audit log shows `role.privileged_bootstrap`. The user's Access page shows the role
granted with no approval reference and with the bootstrap reference.

## Existing holders

EXAM_CONTROLLER assignments made before Increment 2.1 gained the question permissions through the system role. They are
eligible approvers. No bootstrap is needed where such a holder exists.

## Schema owner

The triggers bind the application database role. The schema owner is exempt, because it runs migrations and the
bootstrap and could change the schema anyway. Restrict owner credentials to operators. Log their use (database audit
logging), and never configure the application's request-serving connection pool with them.
