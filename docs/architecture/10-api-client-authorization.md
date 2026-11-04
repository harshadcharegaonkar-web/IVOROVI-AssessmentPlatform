# 10 — API-client authorization model (KI-59)

Status: **proposal for approval** (2026-11-04). Implementation starts after the decisions in §7 are approved. This
document answers Part A of the “Increment 1.7 Acceptance” message: an API client must not keep a permission once the
authorization behind it is gone.

## 1. The problem

Today (increment 1.7) an API client's scopes are checked once, at registration, against the registering
administrator's permissions. After that the scopes are a frozen copy:

| Event after registration | Today | Required |
|---|---|---|
| A permission is removed from the creator's role | client keeps it | lost immediately |
| The creator's role assignment is revoked or expires | client keeps it | lost immediately |
| The creator is suspended or deactivated | client keeps working | approved disablement or reassignment workflow |
| A role definition is narrowed | client keeps it | lost immediately |
| A long-running integration must survive staff turnover | no supported way | an independently authorized identity |

Token expiry and manual rotation do not address this: they shorten the window, they do not close it.

## 2. Recommended model: two kinds of machine identity, both evaluated live

```
                      ┌──────────────────── per request (GET/HEAD) ───────────────────┐
 X-Api-Key ─► key id ─► organization ─► client row ─► kind?                           │
                                                      │                               │
                     DELEGATED (owned by a person)    │    SERVICE (own identity)     │
   effective = client scopes                          │    effective = client scopes  │
             ∩ owner's CURRENT grants at the client   │              ∩ the service    │
               unit (live principal, shared cache)    │                identity's     │
             ∩ READ-only ∩ never-allowed list         │                CURRENT role   │
   owner must be ACTIVE, else 401 owner_inactive      │                grants         │
                                                      │              ∩ approved scope │
                                                      │                envelope       │
                                                      │              ∩ READ-only ∩    │
                                                      │                never-allowed  │
                      └────────────────────────────────────────────────────────────────┘
```

### 2.1 Delegated API clients (the existing kind, made safe)

A delegated client acts **on behalf of its owner** and can never do more than the owner can do *now*.

* Every authenticated request computes `scopes ∩ owner's current grants` (scoped to the client's unit, using the same
  ltree subtree rule as everywhere else) and builds the principal from the result. A scope the owner has lost simply
  disappears from the principal for that request; endpoints then deny it exactly as they deny a user.
* The owner's grants come from the existing `PrincipalCache`: it is shared by all API instances (Valkey) and is
  **evicted immediately after commit** whenever an assignment, account status or role definition changes (the
  mechanism users already rely on since 1.3). The 60-second TTL is only a fallback; it is not what revocation depends on.
* Owner not ACTIVE (suspended, deactivated, invited) → the key is refused (`401 api_client_owner_inactive`), even before
  the lifecycle workflow (2.3) runs.
* Registration still checks that the owner holds every scope (no escalation); the live check then keeps it true.

### 2.2 Service identities (new, for long-running integrations)

A service identity is a **non-human principal of the organization** with its own role assignments, so it does not
depend on any employee staying.

* Created by an `integration.manage` holder with: name, purpose, accountable custodian (a person), unit, and an
  **approved scope envelope** (a set of permissions; in Phase 1 READ-only). The envelope is the hard ceiling.
* It receives roles through the normal role-assignment rules (`role.assign`, ceiling rule: the assigner must hold the
  permissions at that unit). Assigning a role whose permissions exceed the envelope is refused; if a role definition
  later grows, the extra permissions are filtered out by the envelope at request time.
* Effective permissions per request = `key scopes ∩ identity's current role grants ∩ envelope ∩ READ-only ∩ never-allowed`.
  Revoking the identity's role, narrowing the role, or narrowing the envelope takes effect on the next request (same
  cache eviction).
* Its API clients belong to the identity, not to a person, so staff changes do not break them. The custodian is
  accountable (receives notifications, must re-certify periodically) but grants nothing.
* Service identities cannot sign in interactively, cannot hold `integration.*`, `audit.read`, `role.*`, `user.manage`
  or `student.personal.read`, and are listed separately in Users → Service identities.

### 2.3 Lifecycle workflow when an owner is suspended or deactivated

1. The `user.suspended` / `user.deactivated` event (already on the outbox) is handled by the integrations module:
   every ACTIVE delegated client of that user moves to **SUSPENDED_OWNER_INACTIVE** in the same organization context,
   audited (`api_client.suspended`, reason `owner_inactive`), and integration managers are notified.
2. An integration manager then chooses, per client:
   * **Reassign** to another ACTIVE user: allowed only if the new owner holds every scope at the client unit now
     (re-validated, audited `api_client.owner_changed` with old and new owner ids); the client resumes.
   * **Convert** to a service identity's client (if approved in §7): scopes must fit the identity's envelope.
   * **Revoke** permanently.
3. If the owner is **reactivated**, clients stay suspended until a manager resumes them (no silent restoration).
4. Independently of step 1, the live owner check in 2.1 already blocks every request, so there is no window while the
   event is in flight.

### 2.4 What is audited

| Change | Audit action | Details (no secrets) |
|---|---|---|
| Client created | `api_client.created` | kind, owner or service identity, scopes, unit, key id |
| Owner changed | `api_client.owner_changed` | old owner, new owner, scopes re-validated |
| Scopes narrowed | `api_client.scopes_changed` | removed scopes (widening = new client) |
| Suspended / resumed | `api_client.suspended` / `.resumed` | reason (`owner_inactive`, `manual`, `custodian_missing`) |
| Key rotated / revoked, client revoked | existing actions | key id, grace period, reason |
| Service identity created / changed / disabled | `service_identity.*` | envelope before/after, custodian, unit |
| Role assigned to / revoked from a service identity | existing `role.assigned` / `role.revoked` | principal type `SERVICE` |
| Request denied because a scope was lost | usage counter `denied`, plus one `api_client.scope_lost` audit per client per scope per day | scope, reason (`owner_lacks`, `envelope`, `role_revoked`) |

The per-day de-duplication for `scope_lost` keeps the hash-chained trail useful when an integration keeps retrying.

## 3. Alternatives considered

| Option | How it works | Why not recommended |
|---|---|---|
| A. Shorter key expiry / forced rotation | Keys live hours or days | Explicitly excluded; a revoked permission still works until expiry |
| B. Event-driven scope stripping | On every role/assignment change, find affected clients and delete scopes from their rows | Must enumerate every path that changes grants (role edits, unit moves, expiring assignments, status changes); a missed path leaves a permanent hole; expiring assignments have no event; restoring access needs manual re-grant |
| C. OAuth2 client credentials in Keycloak (token exchange) | Clients get short-lived tokens from the IdP | Revocation relies on token lifetime (same problem as A); moves authorization data into the IdP, where our unit-scoped grants do not exist |
| **D. Live intersection (recommended)** | Effective permissions computed per request from current grants | Uses the existing, tested cache-eviction path; no enumeration of change paths; expiring assignments handled by the same `refreshBy` boundary as for users |
| E. Live intersection without service identities | Only delegated clients | Every staff change breaks integrations, which pushes administrators to own all clients personally (concentrating risk) |

## 4. Security trade-offs of the recommendation

| Benefit | Cost or risk | Mitigation |
|---|---|---|
| Revocation is immediate and complete for every change path, including expiring assignments | One extra principal lookup per API-key request (cache hit ≈ <1 ms; miss = one indexed query) | Measured in the 1.7 benchmark: API-key requests already cost ≈ 1 ms more than bearer; target remains < 3 ms added at p50 |
| Delegated clients can never exceed the owner | An owner's role change silently narrows the client; the integration sees 403s | `scope_lost` audit + notification to the owner and integration managers; client detail shows “effective scopes now” |
| Integrations survive staff turnover via service identities | A new principal type to govern (risk of “orphan” identities) | Mandatory custodian, envelope ceiling, quarterly re-certification reminder, listed separately, cannot hold administrative permissions |
| Owner suspension blocks clients instantly | Business process can stop when one person is suspended | That is the intent for delegated clients; long-running integrations should use service identities |
| Shared Valkey cache makes eviction cluster-wide | If Valkey is unavailable, principals are resolved from the database on every request | Already the platform's behaviour (fail-safe, slower, never stale) |

Residual risk: between a change committing and the after-commit eviction there is a window of milliseconds in which an
in-flight request may complete with the old grants. This is the same window that applies to user sessions and is
accepted platform-wide.

## 5. Data model changes (additive)

* `api_client`: `kind` (`DELEGATED` | `SERVICE`), `owner_user_id` (DELEGATED, required; backfilled from `created_by`),
  `service_identity_id` (SERVICE), status gains `SUSPENDED`, `suspended_reason`; check constraint: exactly one owner.
* `service_identity`: id, tenant, name, purpose, unit, custodian_user_id, scope_envelope text[], status
  (ACTIVE/DISABLED), recertify_by, audit columns; RLS like every tenant table.
* `role_assignment`: `principal_type` (`USER` | `SERVICE`) with `service_identity_id`, or a separate
  `service_role_assignment` table (§7 D-KI59-4).
* No change to keys or hashing.

## 6. Automated security tests to add

1. Removing a permission from the owner's role → next request with that scope is 403; other scopes still work.
2. Revoking the owner's role assignment → 403 immediately; the assignment expiring (`valid_to`) → 403 at the boundary.
3. Narrowing a role definition (tenant-wide eviction) → 403 for every delegated client of its holders.
4. Owner moved to a unit outside the client's unit → scopes at the old unit lost.
5. Owner suspended → `401 api_client_owner_inactive` immediately; event handler sets the client SUSPENDED; managers notified; reactivating the owner does not resume it.
6. Reassignment: refused when the new owner lacks a scope; accepted otherwise; audited with both owners.
7. Service identity: cannot be given a role beyond its envelope; narrowing the envelope removes the permission on the next request; revoking its role removes it; it keeps working when its custodian is deactivated (custodian flag raised); it can never hold the never-allowed permissions.
8. Cross-organization: a service identity or client of organization A can never be resolved for organization B.
9. Audit: every lifecycle change produces exactly the documented action; no key or secret appears in the trail.
10. Regression: all 1.7 API-client tests unchanged (key shown once, hashing, IP rules, expiry, rotation, read-only).

## 7. Decisions requiring approval

| # | Decision | Recommendation |
|---|---|---|
| D-KI59-1 | Authorization model | Live intersection for delegated clients (option D) |
| D-KI59-2 | Introduce service identities now (Increment 1.7 closure) or defer to Phase 2 | **Now**, READ-only, so long-running integrations do not depend on a person |
| D-KI59-3 | Owner suspended/deactivated | Suspend the owner's delegated clients; manager reassigns, converts or revokes; no automatic resume on reactivation |
| D-KI59-4 | Storage of service-identity roles | Reuse `role_assignment` with a principal type (one authorization path, one ceiling rule) |
| D-KI59-5 | Envelope changes | Single approver with `integration.manage` + audit + custodian notification (a second approver can be added in Phase 2's approval workflows) |
| D-KI59-6 | Custodian leaves | Identity keeps working, flagged “custodian required”, managers notified; disabled automatically after 30 days without a custodian |

Estimate after approval: 2–3 days including migration, UI (owner, effective scopes, service identities, suspended
state, reassignment), the tests in §6, docs and CI.
