# Increment 1.2: user and staff management

* Status: **Implemented and verified; awaiting product-owner review** (authorized 2026-11-02 with the 1.1 acceptance). Increment 1.3 not started.
* Depends on: 1.1 (accepted subject to the final checks, recorded in `increment-1.1.md`).
* **Sequence change (product owner, 2026-11-02):** users and staff are 1.2 (the plan had them as 1.3); custom roles and permission configuration are 1.3. The academic calendar moves after them. Plan sections 3.2/5.3/5.4 apply under the new numbers.

## Scope delivered

| Area | Delivered |
|---|---|
| Users | Create (status `INVITED`), view, edit profile (first/last name, email, phone), search and filter (status, invitation status, unit subtree, role), sort, pagination; last sign-in (token `auth_time`) |
| Lifecycle | Suspend ↔ reactivate (temporary), deactivate ↔ activate (left the organization); reason required to suspend/deactivate; login disabled and sessions ended in Keycloak; access ends on the next API request (principal cache evicted) |
| Invitations | Single-use, expiring (7 days, `IVOROVI_INVITATION_TTL`), 256-bit tokens stored only as SHA-256 hashes; link in the URL **fragment**; resend replaces the token; revoke; expired state; one open invitation per user; delivery status (sent/failed); public lookup and acceptance with per-IP rate limit |
| Keycloak | Account created only on acceptance, through the confidential client `ivorovi-provisioner` (realm-management `manage-users`, `view-users`, `query-users` only — verified: clients, roles and the master realm return 403). Published port `IdentityProvisioner` |
| Staff | Profile independent of a login (staff ID unique per organization, case-insensitive; category; designation; contact; employment status and type; joining date); link to / unlink from a user account (one-to-one) |
| Organizational assignments | Users and staff at organization, university, college, campus or department level; several per person; primary; title; optional dates; removing the last one is refused |
| Role assignments | Existing system roles (except platform super admin and student) at the organization or any unit; optional end date; revoke with reason (soft, history kept); duplicate refused |
| Access overview | Account and invitation status, assignments (own and via staff profile), active roles, effective permissions with the scopes and roles that grant them |
| Dashboard | People KPIs: active users, pending invitations, active staff |

Excluded as instructed: student accounts and lifecycle, custom roles, permission configuration, academics, bulk import, institution SSO.

## Security rules

* **Scope of people.** A person's *units* are their organizational assignments (direct and through a linked staff profile) and the scopes of their active role assignments (organization = whole tenant). A person is visible with `user.read`/`staff.read` on **any** of those units and manageable only with `user.manage`/`staff.manage` on **all** of them, so a college administrator can never act on someone who also belongs to, or holds a role in, another part of the organization.
* **Anti-escalation.** Assigning or revoking a role needs `role.assign` at the target scope **and** every permission of the role at that scope. Self-assignment and changes to one's own account are refused. Denials are audited (`role.assignment_denied`, `role.self_assignment_denied`).
* **Last organization administrator** cannot be suspended, deactivated or have the role revoked (`409 last_organization_admin`, advisory lock per tenant).
* **Email** is trimmed and lower-cased (database check constraint), unique per organization. One Keycloak login belongs to one organization; an invitation to an address already registered for another organization is refused at acceptance (`email_registered_elsewhere`) without revealing it to the inviting administrator (KI-33).
* **Tokens** never appear in logs, audit details or outbox payloads (tested); the acceptance page removes the fragment from the address bar; request objects redact passwords in `toString`.
* **Failure order.** Keycloak is changed before the database commits; if Keycloak is unavailable the operation returns `502 identity_provider_unavailable` and nothing changes (tested with a simulated outage).

## Database (`V202611021200__identity_users_staff.sql`)

`user_account` gains given/family name, phone, status reason/time/actor; `external_subject` becomes nullable for invited users (partial unique index; check: required unless INVITED/DEACTIVATED); normalised-email check. New tenant-RLS tables `user_invitation`, `staff_profile`, `org_assignment`; `role_assignment` gains soft revocation. `invitation_tenant(hash)` is a `SECURITY DEFINER` function returning only the tenant of a token hash, so the public page can then run inside normal RLS. Permissions `role.read`, `role.assign`, `staff.read`, `staff.manage` added to system roles (ORG_ADMIN, INSTITUTION_ADMIN; read-only for CAMPUS_ADMIN, DEPARTMENT_HEAD, MANAGEMENT). Existing accounts receive organizational assignments derived from their grants (the TENANT scope becomes the organization). Unit deletion now also counts organizational assignments as dependents.

**Upgrade of the existing development database** (Phase 0 + 1.1 data, 605 accounts): 605 accounts and 605 role assignments preserved; 605 organizational assignments created, one primary each; seeded users map to their units (Alice → organization, Ines/Arun/Asha → AIT).

## APIs (`/api/v1`, OpenAPI in `docs/api/openapi.json`, generated client)

| Method | Path | Permission |
|---|---|---|
| GET / POST | `/users` (POST: Idempotency-Key) | `user.read` (scope-filtered) / `user.manage` at the unit |
| GET / PATCH | `/users/{id}` | visible / manageable |
| POST | `/users/{id}/status` `{action: SUSPEND\|REACTIVATE\|DEACTIVATE\|ACTIVATE, version, reason}` | manageable, not self |
| POST | `/users/{id}/invitation` | manageable (send or re-send) |
| GET | `/users/{id}/access` | visible |
| GET / POST / DELETE | `/users/{id}/org-assignments[/{aid}]`, `…/{aid}/primary` | visible / manageable + target unit |
| GET / POST | `/users/{id}/role-assignments`, `…/{aid}/revoke` | visible / `role.assign` + anti-escalation |
| GET | `/roles` | `role.read` |
| GET / POST | `/invitations`, `/invitations/{id}/revoke` | `user.read` / manageable |
| POST | `/public/invitations/lookup`, `/public/invitations/accept` | none (token is the credential; 20 req/min/IP) |
| GET / POST / PUT | `/staff`, `/staff/{id}`, `/staff/{id}/link`, `/unlink`, `/org-assignments…` | `staff.read` / `staff.manage` (+ `user.manage` to link) |

## Screens

User listing; user details (Overview, Organization, Roles, Access); create/invite and edit user; status dialogs; assign role and revoke dialogs; organizational assignment dialog; invitation management; staff listing; staff details (Overview, Organization); create/edit staff; link user account; public invitation acceptance (outside the signed-in shell, lazy-loaded); People navigation group; dashboard people KPIs.

## Results

| Check | Result |
|---|---|
| Backend (JUnit + Testcontainers) | **135 tests** incl. `UserManagementIT` (16), Modulith, permission-catalog consistency, OpenAPI export, 1.0/1.1 suites |
| Smoke test, real Keycloak tokens | **33 passed**: create + invite, email through Mailpit, public lookup, accept (Keycloak login created by the provisioner), single use, real sign-in, role-less user denied, deactivate → token rejected at once and Keycloak refuses sign-in |
| Frontend unit tests | **37 passed** |
| Typecheck / lint | clean |
| Bundle | initial **174.4 kB gzip** (≤ 180 kB); largest lazy chunk 34.2 kB (≤ 40 kB); the invitation page is a separate chunk |
| Playwright desktop 1440 / tablet 1024 / mobile | **51 passed** (1.0 and 1.1 suites unchanged and passing): users/filters/access overview, invite → public accept → Keycloak sign-in → assign/revoke role → suspend (Keycloak then refuses sign-in) → deactivate, resend replaces link and revoke, staff without login + duplicate staff ID + second assignment + link to user, scoped admin (own subtree, escalation refused, no self-service), unknown link, no horizontal scrolling on every new screen |
| Accessibility | axe WCAG 2.1 AA, no serious/critical violations on users list, user access, invitations, staff detail, invitation page |
| GitHub Actions | recorded in the tracker |

Acceptance criteria 1–20 map to the tests above (creation/invitation 1–3: `Invitations` nested tests + smoke + E2E; lifecycle 4: `Lifecycle`; staff 5–6: `Staff`; assignments and scope 7–9: `Assignments`; escalation 10; cross-tenant 11: `Isolation`; audit 12: assertions on audit rows in each group; cache 13: suspension/role revocation take effect on the next request; migration 14: upgrade + `phase0AndIncrement11AccountsWereMigratedWithAssignments`; 15–20: suites, smoke, Playwright, axe, regressions, CI).

### Defects found during verification
* Invited users without a login could not be deactivated (check constraint) — fixed, tested.
* Contrast of the sign-in email label on the acceptance page — fixed (axe).
* Assignments inherited from a linked staff profile were listed twice in the access summary without their source — labelled.
* Mobile user table clipped the status column — status shown under the name on phones.

### Not delivered / deviations
* Pixel-diff visual baselines (KI-26) still open.
* Email templates are plain text; branded templates and notification preferences remain Phase 2.
* Changing the email of a user who has signed in is not supported here (the identity provider owns it; `email_locked`).
* Bulk user import and faculty teaching attributes from plan amendment 1 are outside the 1.2 scope given by the product owner.

Evidence: `docs/design/evidence/1.2/` (41 screenshots, 1440×900, 1024×768, 390×844).

## Acceptance security verification (2026-11-03)

The product owner approved 1.2 and asked for six security verifications. Each is an automated test in
`UserManagementIT.SecurityVerification` (real PostgreSQL, Valkey and RabbitMQ), plus a live probe in the smoke test.

| # | Verification | Evidence | Result |
|---|---|---|---|
| 1 | Concurrent attempts cannot remove the last organization administrator | `item1_…`: two administrators deactivate each other at the same moment (5 rounds), then revoke each other's ORG_ADMIN at the same moment: exactly one request succeeds each time, one active administrator always remains (per-tenant advisory lock) | Pass |
| 2 | Administrators cannot manage users whose assignments extend outside their scope | `item2_…`: a college administrator can suspend a user of their college; after the user gains an assignment (or a role) in another college, status change, profile edit, new assignment and invitation are all 403 | Pass |
| 3 | Invitation tokens single-use, expiring, hashed, never logged | `item3_…` captures all application log output during invite, re-send, accept, re-use and expired lookup: neither token nor password appears; only hashes are stored; plus the 1.2 tests for audit/outbox | Pass |
| 4 | Provisioning service account remains least-privilege | `item4_…` asserts the realm definition (confidential, service account only, no browser or password flow, secret is an environment placeholder, exactly `manage-users`/`view-users`/`query-users`, no realm roles); smoke test probes real Keycloak: users 200, clients 403, roles 403, master realm 403 | Pass |
| 5 | Revoked, expired or not-yet-valid roles grant no access after cache invalidation | `item5_…`: an assignment ending in 3 s works, then stops at its end time; one starting in 3 s is refused, then works; a revoked one stops on the next request | Pass after fix (below) |
| 6 | Lifecycle and permission changes are auditable | `item6_…`: create, invitation sent/accepted, role assigned/revoked, suspended/reactivated/deactivated are recorded with the acting administrator, target and reason; chain verification valid | Pass |

**Defect found by item 5 and fixed:** cached principals lived up to 60 s, so an assignment that reached its end
date kept granting access for up to a minute (and a future-dated one started up to a minute late). The resolver now
records the next start/end time of the user's assignments and the cache entry expires no later than that
(`IvoroviPrincipal.refreshBy`, `PrincipalCache`).
