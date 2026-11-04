# Runbook — API clients and service identities (KI-59)

Audience: organization administrators holding `integration.manage`, and platform operators. Model:
`architecture/10-api-client-authorization.md`.

## Concepts in one paragraph

Every API-key request is authorized from **current** state. A *delegated* client acts for its owner and can do at most
what the owner can do at that moment. A *service identity* is a read-only machine identity that has its own roles,
limited to an envelope another integration manager approved. Two people, a business and a technical custodian, are
accountable for it. Key expiry and rotation are hygiene; they are not how access is withdrawn.

## Daily administration

| Task | Where | Rules |
|---|---|---|
| Register a delegated client | Configuration → API Clients → Register client → “Acts for: Me” | You must hold every scope (organization-wide unless you restrict the client to a unit). Read-only scopes only |
| Create a service identity | Configuration → Service Identities → New | Name, purpose, unit, business and technical custodian (active users), envelope. Starts **Pending approval** |
| Approve an envelope | Service identity → Proposed envelope → Approve | Must be someone other than the proposer, who holds every proposed permission at the identity's unit. Approval is audited |
| Narrow an envelope | Service identity → Change envelope (remove items) | Applies immediately to every key of the identity |
| Widen an envelope | Service identity → Change envelope (add items) | Becomes a proposal; nothing changes until someone else approves it |
| Assign a role to an identity | Service identity → Assign role | Role permissions must lie inside the envelope; you must hold them at the chosen unit |
| Register a client for an identity | API Clients → Register client → “Acts for: Service identity” | Scopes limited to the envelope; effective access = identity's role grants ∩ envelope |
| Check what a client can do now | API client → “Effective now” | Lost scopes are listed with the reason (owner no longer holds it, no role grants it, outside envelope) |

## When a person leaves or changes role

* **Owner suspended or deactivated:** keys of their delegated clients are refused at once
  (`401 api_client_owner_inactive`). The clients are then marked *Suspended (owner inactive)*, and integration managers
  are notified. Choose one of:
  1. **Reassign** to another active user who holds every scope over the client's whole scope right now. The client
     resumes.
  2. **Revoke** the client.

  Reactivating the former owner does **not** resume their clients. Resume manually, if it is still appropriate.
* **Owner's role narrowed or assignment revoked/expired:** the affected scopes stop working on the next request
  (`403`). One audit record per client, scope and day (`api_client.scope_lost`). Restore the permission, reassign, or
  narrow the client's scopes.
* **Custodian suspended or deactivated:** the identity keeps working, flagged “custodian no longer active”, and
  managers are notified. Name active custodians within **30 days**. Otherwise the identity is suspended automatically
  (`service_identity.suspended`, reason `custodian_missing`), and all its keys are refused until it is resumed.

## Recovery procedures

| Situation | Steps |
|---|---|
| Integration suddenly gets 401 `api_client_owner_inactive` | Owner left. Reassign the client (or move the integration to a service identity: register a new service client, deploy its key, revoke the old client) |
| 401 `api_client_suspended` | Open the client; read the reason; fix the cause (owner/identity) and **Resume**, or reassign |
| 401 `api_client_identity_inactive` | Identity suspended or pending. Fix custodians or get the envelope approved, then **Resume** the identity |
| 403 on some endpoints only | Client detail → “Effective now / lost scopes”. Restore the owner's or identity's role, or approve the envelope |
| Key leaked | Rotate with grace 0 (old keys stop at once) or revoke the client; review usage (daily requests/denied) and the audit trail |
| No approver available (single administrator) | A second user must be given `integration.manage` (role assignment, audited). The proposer can never approve their own envelope |
| Identity no longer needed | **Disable** (permanent): its roles and clients are revoked |

## Operator notes

* Lifecycle events arrive through RabbitMQ (`ivorovi.integrations.lifecycle`). If the queue is down, keys are still
  refused (the live check does not depend on events). The status change catches up when the queue recovers.
* Custodian checks run hourly (`ivorovi.integrations.custodian-check-cron`); the 30-day rule is evaluated against
  `custodian_invalid_since`.
* Usage counters are written in batches (every 10 s, and on shutdown). Authorization never uses them.
* Audit actions: `api_client.created/owner_changed/scopes_changed/suspended/resumed/scope_lost/rotated/key_revoked/
  revoked`, `service_identity.created/envelope_proposed/envelope_approved/envelope_approval_denied/envelope_rejected/
  envelope_narrowed/custodians_changed/custodian_invalid/role_assigned/role_revoked/suspended/resumed/disabled`.
