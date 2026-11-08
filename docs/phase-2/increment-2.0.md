# Increment 2.0 — Foundations: implementation record

Status: **conditionally accepted** by the product owner on 2026-11-08, on the basis of this report and CI run
**42444423260** (all jobs green: backend, frontend, security scan, end-to-end) on commits `79f07c2`, `a5c7564`, `fbcc47c`.
Accepted: four-eyes approval framework; separate candidate realm; candidate MFA, recovery and session codes; separate
delivery process and security boundaries; outage and compensation workflow; event publishing; job leasing; email
notifications; load and failure harness; distinct business and technical custodians; KI-65 fix.

**Conditions** (mandatory follow-ups before examination-delivery implementation, tracked in
`increment-2.0-follow-ups.md`): A — layered candidate rate limiting for shared campus addresses (KI-66);
B — outage detection beyond total failure (per instance, partial, candidate/session impact, operator dashboard,
correlation and recovery, compensation integration; KI-71, KI-72, KI-73); C — answer-persistence requirements recorded
as mandatory for 2.5; D — complete register for KI-66 … KI-73. B must be completed and tested before real examination
delivery.

Authorized 2026-11-04 (plan: `increment-2.0-plan.md`, extra acceptance requirements §7a). **No production-readiness
claim**: the release blockers in `../runbooks/production-readiness.md` stay open (malware scanner, key management and
rotation, KI-46, KI-48 legal review and workflows, penetration test) and real-candidate processing stays blocked (C-7).

## 1. What was built, per part

| Part | Delivered |
|---|---|
| **A — Four-eyes** (C-2, Q-04) | `approvals` module; service rule + DB constraints `approval_four_eyes` / `approval_decision_consistency` + live permission check; expiry, cancellation, escalation; notifications to eligible approvers only; audit of every step; staff inbox `/approvals` and detail page. ADR-0007 |
| **B — Candidate accounts** (C-1, Q-02, Q-03) | Candidate realm `ivorovi-candidates` (realm as code): verified email first, brute-force lockout, short sessions, TOTP + recovery codes, MFA policy per portal (OFF/OPTIONAL/REQUIRED). Reset requires the second factor; every session ends after a reset (`RecoveryGuard`); audited staff MFA reset. Expiring single-use session codes (keyed hash, atomic consumption, per-candidate/address/code limits, uniform refusals, audit). ADR-0008 |
| **C — Exam app and boundaries** (C-4, P2-03, Q-07) | `IVOROVI_PROCESS_ROLE` ADMIN/DELIVERY with 404 boundary; separate delivery security chain (candidate issuer/keys/audience only); tenant from `/p/{portal}` server-side; CORS exam origin only; `frontend/apps/exam` (sign-in, registration, MFA set-up, system check with clock offset and non-extractable device key, check-in, presence, compensation); strict CSP and headers; own bundle budget; compose services `delivery` and `exam`. ADR-0008 |
| **D — Outage and compensation** (C-3) | Process heartbeats; leased outage detector (45 s); platform incidents and per-organization entries with affected candidates; institution-reported outages; compensation proposal → four-eyes approval → bound single-use entitlements + candidate emails; staff incident screens; runbook. Policy defaults stay open (Q-06) |
| **E — Events, leases, email** (KI-61, KI-63, KI-64, P2-09) | Job leases for every once-only job; parallel outbox publisher (≥ 11,000 events/s measured); email channel with preferences, retries/backoff, FAILED records, encrypted recipients; preferences on the Notifications page |
| **F — Load and failure** (Q-11) | Single-file JDK harness (`backend/devtools/harness/DeliveryHarness.java`), failure script, outbox benchmark script; results in `../performance/` |

Also: custodians must be two different people (Q-01, service + DB constraint `service_identity_distinct_custodians`);
KI-65 (flaky 1.1 menu test) fixed; Phase 1 code touched only as listed in plan §6 plus a `JobLeases` wiring fix.

## 2. Migrations and security impact

| Migration | Content | Security notes |
|---|---|---|
| `V202611081100` | distinct custodians | conflicting identities disabled and their clients revoked before the CHECK is added |
| `V202611101000` | `job_lease`, `notification_preference`, `email_delivery` | lease table platform-only RLS; recipient encrypted |
| `V202611101100` | `approval_request` | RLS; four-eyes CHECK; one open request per subject |
| `V202611101200` | exams tables, `process_heartbeat`, `platform_incident`, `exam_portal_tenant()`; grants of 3 new permissions to ORG_ADMIN, INSTITUTION_ADMIN, EXAM_CONTROLLER | every tenant table RLS; heartbeats/incidents platform-only (organizations read incidents that affected them); security-definer lookup returns only active portals of active organizations |
| devdata `V202611101300`, `V202611101301` | synthetic second exam controller (dev only) | not loaded outside the dev profile |

New secrets/configuration: candidate provisioner secret (`IVOROVI_CANDIDATE_IDP_SECRET`), delivery issuer/JWKS, exam
origins. No secret is committed (`.env` is generated by `scripts/dev-setup.sh`). Rate limits: delivery per candidate
240/min, per organization 120,000/min, code attempts as above. Hardening found while testing: Keycloak's default reset
flow removed the authenticator after an email link (MFA bypass) — replaced; Keycloak does not force sign-out after a
reset — enforced by the platform (KI-67).

## 3. Test results (local, 2026-11-05 … 08)

| Suite | Result |
|---|---|
| Backend (unit + integration, Testcontainers) | 283 tests; last full run 282/283 with one Phase 1 data check failing on the new dev user's missing organization assignment → fixed (devdata `V202611101301`), test re-run green. New: `ApprovalsIT` 6, `DeliveryIT` 13, `ExamAdminIT` 5, `IncidentWorkflowIT` 3, `ReliabilityIT` 4 |
| Frontend unit (vitest) | api-client 3, ui 11, web 39, exam 8 — all green; lint and typecheck clean |
| Staff app E2E (Playwright, real Keycloak) | **102/102** |
| Exam app E2E (production build with production headers, real candidate realm, desktop/tablet/mobile + axe) | **8/8** |
| Bundles | staff initial 158.6 kB gzip (≤ 180); exam initial 88.0 kB (≤ 120); boundary check: no staff code in the exam bundle |

CI: run 42444423260 — green on all jobs (backend 283 tests, frontend, security scan, end-to-end with staff and exam suites in the compose stack).

## 4. Load and failure results

`../performance/delivery-load-and-failure.md`: 2,000 virtual candidates for 10 minutes — 213,878 requests, 0 errors,
0 lost acknowledgements, p95 39 ms (heartbeats and saves), delivery process ≤ 40 % of 4 cores and ≤ 745 MB. Failures:
Valkey and RabbitMQ restarts 0 errors; 10 s database pause 23 timeouts then recovery; delivery crash detected after 47 s,
resolved 16 s after restart with all 200 affected candidates recorded. Outbox: 11,658 events/s
(`../performance/outbox-throughput.md`).

## 5. Known issues and deferred items

New: KI-66 (per-address code limit and campus NAT), KI-67 (reset sign-out enforced within ~10 s), KI-68 (realm behaviour
tied to Keycloak 26.8), KI-69 (reload needs SSO redirect), KI-70 (email digest, bulk summary events, per-tenant outbox
fairness), KI-71 (only whole-role outages detected automatically), KI-72 (no platform-operator incident screen), KI-73
(candidates who never checked in are not listed as affected). Resolved: KI-61, KI-63, KI-64, KI-65 (pending CI
confirmation). Deferred by design to 2.5: attempt-scoped tokens bound to the device key, idempotent answer saves,
per-attempt compensation mechanics. Open decisions untouched: Q-05, Q-06, P2-07 (see register, notes N-2.0-1…8).

## 6. Demonstration

Walkthrough on the dev stack (synthetic data only): candidate registers on a portal with email
verification → system check → checks in with a single-use code (presence every 15 s) → staff see the check-in and
generate codes (shown once) → an outage is reported → compensation proposed → the proposer cannot approve → a second
controller approves from the approvals inbox → the candidate is emailed and uses the compensation once. The same flow is
automated in `frontend/apps/exam/e2e/candidate.spec.ts`; screenshots are captured by `e2e/demo.capture.ts`.
