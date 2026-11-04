# Increment 2.0 — Foundations (plan for review)

Status: **planning only — awaiting approval** (2026-11-04). No 2.0 code exists. Prerequisite: Increment 1.7 formally
closed (KI-59 implemented, CI green). Phase 1 functionality is changed only where a documented defect (KI-61, KI-64) or
an approved dependency requires it, and each such change is listed in §6.

## 1. Goal

Put in place the shared foundations that every later Phase 2 increment needs, and that the conditions C-1…C-4 of the
blueprint approval require **before** any assessment content exists:

1. a candidate identity boundary with secure recovery, rate limiting and configurable MFA (C-1);
2. a reusable four-eyes approval framework that makes self-approval impossible (C-2);
3. the separate exam application and delivery process with strict boundaries (C-4);
4. the platform-outage incident and candidate-compensation design (C-3);
5. throughput and reliability fixes the examination engine depends on (KI-64, KI-61), email notifications (KI-63);
6. a load and chaos test harness for the delivery gates from 2.5 onward.

No question bank, assessment, scheduling or delivery business logic is built in 2.0.

## 2. Scope

### 2.0-A Four-eyes approval framework (C-2)

* `approvals` platform component: `ApprovalRequest` (type, subject, payload hash, requester, required permission, unit,
  status, expiry) and `ApprovalDecision` (approver, decision, reason, time).
* **Separation of duties enforced three times:** (1) service rule requester ≠ approver; (2) database check constraint
  `approver_id <> requester_id`; (3) the approver must hold the required permission at the subject's unit *at decision
  time* (live, as in KI-59). Optional rule per type: approver must not have edited the subject since the request
  (payload hash must still match).
* No self-delegation, no platform-staff approval, expiry (default 7 days), cancellation by the requester, notifications
  to eligible approvers, audit for request, decision, expiry and cancellation.
* Organizations with one eligible approver: the step is blocked with a clear message (Q-04).
* First consumer: a test type in 2.0; real consumers from 2.2 (assessment publication). The KI-59 envelope approval
  keeps its own implementation (already enforcing the same rule) and may migrate later without behaviour change.

### 2.0-B Candidate identity boundary (C-1)

* Separate Keycloak realm `ivorovi-candidates` (Q-03) with its own clients, password policy, brute-force detection,
  session lifetimes and themes; staff realm unchanged.
* Self-registration with verified email (mobile verification when an SMS provider is approved, Q-08); disposable-domain
  check; CAPTCHA-free rate limiting per IP, per account and per tenant (existing limiter, new policies).
* Secure recovery: email verification codes plus one-time recovery codes generated at MFA enrolment. Recovery
  invalidates sessions and notifies the candidate. No security questions.
* Configurable MFA: tenant policy (off / optional / required), overridable per assessment purpose later (Q-02). Factors:
  TOTP and email one-time code.
* Candidate profile link: a candidate account maps to a `candidate` record (2.3 creates the full record; 2.0 only the
  identity link and `me` endpoint for candidates).
* **Expiring single-use session codes** (`session_code` service): random 8–10 character codes (no ambiguous characters),
  stored as hashes, scoped to tenant + session (+ candidate for per-candidate codes), valid only in a time window,
  consumed atomically on first successful use. Attempts are rate-limited per code, candidate and IP; usage is audited.
  Delivery uses them from 2.5 (Q-05).

### 2.0-C Exam application and delivery boundary (C-4)

* `frontend/apps/exam`: a separate Vite application and bundle (no staff code, no staff routes) on its own origin
  (Q-07), with its own OIDC client in the candidate realm (token audience `ivorovi-delivery`).
* Browser boundary:
  * strict CSP (no inline scripts or styles; `connect-src` only the delivery API; `frame-ancestors 'none'`);
  * `Referrer-Policy: no-referrer`, `Permissions-Policy` allowing only camera (when proctoring is enabled) and
    fullscreen, COOP/COEP;
  * no shared cookies or storage with the staff app; tokens in memory, plus a non-extractable device key in IndexedDB.
* Delivery process profile `delivery` (P2-03):
  * exposes only `/api/v1/delivery/**` and health endpoints; staff endpoints are not registered;
  * accepts only `ivorovi-delivery` tokens, and the main API rejects them;
  * has separate rate-limit policies, separate CORS (exam origin only) and its own connection pool.
* Attempt-scoped token design (issued from 2.5): short-lived (15 min, refreshed by heartbeat), bound to attempt and
  device key.
* 2.0 delivers the shell (sign-in, MFA, system check page: browser, network, camera permission test, clock offset), the
  boundaries and their security tests.

### 2.0-D Platform-outage incident and compensation process (C-3)

* Design and runbook: platform incident record (start/end, affected tenants/sessions/attempts, cause, evidence).
  Compensation actions (deadline extension per attempt, re-sit eligibility, attempt voiding) are proposed by the exam
  controller and approved through 2.0-A. Candidates are notified, and every action is audited. The server deadline is
  never changed outside this workflow.
* 2.0 builds the incident register and the approval types. The per-attempt mechanics ship with delivery (2.5).
* Policy defaults await the institution and product decision (Q-06).

### 2.0-E Event and job reliability (KI-64, KI-61) and email channel (KI-63)

* Outbox publisher: parallel batches with per-tenant fairness and a larger batch. Target ≥ 2,000 events/s sustained
  (from 100/s). Summary events for bulk operations where consumers allow.
* Scheduled jobs (integration health checks and future sweepers) claim work with database leases, so each job runs on
  exactly one instance.
* Email notification channel (P2-09): per-user preferences per category, digest option, templates, delivery status
  (`SENT`/`FAILED`), unsubscribe for non-essential categories. Uses the existing mail adapter; no new provider.

### 2.0-F Load and chaos test harness

* JDK-`HttpClient` based scripted-candidate harness in `backend/devtools` (no new library, Q-11). It drives N
  concurrent virtual candidates (sign-in, heartbeats, answer saves, submit) against the delivery API shell. It reports
  p50/p95/p99, errors and lost acknowledgements, and is used as the gate harness from 2.5.
* Chaos scripts: kill delivery process, drop network (iptables in the dev container), restart Valkey and RabbitMQ.
  Assertions on recovery.

## 3. Architecture decisions in this increment

| # | Decision | Status |
|---|---|---|
| P2-03 | Delivery as a process profile of the monolith | Needs approval (recommended) |
| P2-09 | Email notification channel | Needs approval (recommended) |
| Q-02 | MFA factors and default | Needs answer (recommend TOTP + email code, default optional) |
| Q-03 | Separate candidate realm | Needs answer (recommended) |
| Q-04 | Single-approver organizations | Needs answer (recommend block, never self-approve) |
| Q-07 | Exam domain | Needs answer (recommend one domain per region) |
| Q-11 | Harness without new libraries | Needs confirmation |
| ADR-0007 (new) | Four-eyes approval framework | To be written in 2.0 |
| ADR-0008 (new) | Candidate identity boundary and exam application security | To be written in 2.0 |

## 4. Dependencies

* Increment 1.7 closed (KI-59). Decision answers above. Keycloak realm import/automation for a second realm (dev,
  CI and Helm).
* No dependency on legal review for 2.0 (no candidate personal data is collected beyond the account). Notices and
  consent texts are needed in 2.3.

## 5. Estimates

| Part | Engineering days |
|---|---|
| 2.0-A Four-eyes framework | 3–4 |
| 2.0-B Candidate identity boundary + session codes | 5–6 |
| 2.0-C Exam app shell + delivery boundary | 5–6 |
| 2.0-D Incident/compensation design + register | 2 |
| 2.0-E Outbox throughput, job leases, email channel | 4–5 |
| 2.0-F Load/chaos harness | 3 |
| Docs, ADRs, demo, CI, review fixes | 3 |
| **Total** | **25–29 days ≈ 2.5–3 weeks** for 2 engineers (consistent with the roadmap's 2–3 weeks, now including C-1…C-4) |

## 6. Phase 1 code touched (all justified)

| Area | Change | Justification |
|---|---|---|
| `platform.outbox.OutboxPublisher` | parallel publishing | KI-64 (documented defect) |
| `integrations` health checks | lease-based claiming | KI-61 (documented defect) |
| `notifications` | email channel, preferences | P2-09 / KI-63 (approved dependency, when approved) |
| `platform.security` | second token audience/realm for delivery; staff API rejects delivery tokens | C-4 boundary |
| Rate-limit policies | new policies for candidates and delivery | C-1 |

No change to student, academic, import, report or dashboard behaviour.

## 7. Acceptance criteria

1. **Four-eyes:** a requester can never approve their own request (API, UI and a direct database insert all fail). An
   approver who lacks the permission at decision time is refused. Expired and cancelled requests cannot be decided.
   Every step is audited. Cross-tenant tests pass.
2. **Candidate accounts:** registration requires verified email. Brute-force lockout and per-IP/account/tenant rate
   limits are proven by tests. Recovery invalidates sessions and notifies. MFA policy off/optional/required is
   enforced per tenant (TOTP and email code). Staff tokens are not accepted for candidate endpoints, and the reverse
   is also refused.
3. **Session codes:** stored hashed. Single use is guaranteed under concurrency (100 parallel consumers → exactly one
   succeeds). A code is refused outside its window. Attempts are rate-limited and audited.
4. **Exam app boundary:**
   * no staff code is in the exam bundle (bundle analysis);
   * CSP and other headers are verified by automated tests;
   * the delivery profile returns 404 for every staff endpoint;
   * CORS allows only the exam origin;
   * axe is clean on the shell screens (desktop, tablet, mobile).
5. **Incident process:** the incident register exists. Compensation actions require four-eyes approval. The runbook
   is reviewed.
6. **Throughput:** the outbox sustains ≥ 2,000 events/s in the benchmark with no loss or duplication beyond
   at-least-once semantics. Health checks run once per interval with three API instances.
7. **Email:** notifications are delivered to Mailpit according to preferences. Failures are recorded and do not affect
   in-app notifications.
8. **Harness:** 2,000 virtual candidates against the delivery shell (heartbeats and echo saves) for 10 minutes, with
   reported percentiles. The chaos scripts run.
9. No Phase 1 regression: full backend, frontend, E2E and smoke suites are green. The bundle budget holds for the
   staff app; the exam app gets its own budget (≤ 120 kB initial gzip).
10. Docs: ADR-0007, ADR-0008, runbooks (candidate accounts, outage compensation), decision register updated, tracker.

## 8. Test strategy

Unit tests (approval rules, code generation and hashing, MFA policy), Testcontainers integration tests including
concurrency for session codes and approvals, Keycloak realm tests in CI, header/CSP tests for the exam app, a Playwright
project for the exam app (desktop/tablet/mobile + axe), throughput benchmark (outbox), harness smoke run in CI (small
N), and a full run documented in `docs/performance/`.

## 9. Demonstration

Meridian demo:
1. A candidate registers, verifies email, enrols TOTP, signs out, recovers the account with a recovery code, and signs
   in on the exam app's system-check page.
2. An exam controller requests a test approval; the same person cannot approve it; a second controller approves it.
3. A platform incident is recorded, and a compensation proposal goes through approval.
4. The harness runs with 2,000 virtual candidates.

## 10. Risks

| Risk | Mitigation |
|---|---|
| Second Keycloak realm adds operational complexity (backups, upgrades, on-prem) | Realm as code (import/export in CI), documented in deployment runbooks |
| Over-strict CSP breaks later exam features (math rendering, media) | KaTeX and media patterns validated in the shell; nonces where unavoidable |
| Email deliverability in SaaS | Uses the institution's configured relay; delivery status visible; in-app remains the source of truth |
| Scope creep into 2.1+ | Only the items above; anything else needs separate approval |
