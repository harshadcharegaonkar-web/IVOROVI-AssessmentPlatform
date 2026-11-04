# Phase 2 decision register

Updated 2026-11-04 after the product owner's “KI-59 Approval and Phase 2 Blueprint Decisions” message. Status values:
**Approved**, **Approved with conditions**, **Not yet decided** (recommendation stands, to be confirmed when the
increment that needs it is planned), **Open question** (needs an answer before the named increment starts).

## Authorizations in force

| Scope | Status |
|---|---|
| KI-59 implementation | Approved and implemented (Increment 1.7 closes after CI is green) |
| Phase 2 shared assessment engine | **Approved with conditions C-1…C-7** |
| Phase 2 roadmap structure (increments 2.0–2.15) | **Approved with conditions C-1…C-7** |
| Increment 2.0 | **Planning and review only** (`increment-2.0-plan.md`). No implementation until separately approved |
| Increment 2.1 and later | Not authorized |
| DPDP legal review | Start immediately (brief: `../legal/dpdp-review-brief.md`; counsel to be engaged by the product owner) |
| Real-candidate admission testing | **Blocked** until the production blockers are resolved and formally verified (C-7) |

## KI-59 decisions

| # | Decision | Status |
|---|---|---|
| D-KI59-1 | Recalculate effective API-client permissions on every request (scopes ∩ owner's current permissions ∩ read-only) | Approved, implemented |
| D-KI59-2 | Service identities now, read-only initially | Approved, implemented |
| D-KI59-3 | Suspend delegated clients immediately when the owner is suspended or deactivated; no automatic reactivation | Approved, implemented |
| D-KI59-4 | Storage of service-identity roles | Implemented as a separate `service_role_assignment` table (deviation from the proposal, so Phase 1 user functionality stays untouched; same roles and ceiling rule) |
| D-KI59-5 | Envelope changes need **independent** approval | Approved, implemented (approver ≠ proposer, approver must hold the permissions; narrowing immediate) |
| D-KI59-6 | Named business custodian **and** technical custodian; suspension after 30 days without a valid custodian | Approved, implemented |
| D-KI59-7 | Audit of ownership, scope, approval and lifecycle changes | Approved, implemented |

## Conditions attached to the blueprint approval

| # | Condition | How the blueprint incorporates it | Where |
|---|---|---|---|
| C-1 | Candidate accounts: secure recovery, rate limiting, expiring single-use session codes, configurable MFA | Separate candidate identity boundary; verified email/mobile; recovery via verified channel + recovery codes; per-IP and per-account rate limits and lockout; session codes hashed, single-use, short-lived, rate-limited; tenant-configurable MFA (off/optional/required, by purpose) | 2.0 (identity foundation, session-code service), 2.3, 2.5 |
| C-2 | Four-eyes approval must prevent one person performing both steps | Generic approval component: requester and approver recorded, **server-side and database-enforced** inequality, approver's own permission checked, no self-delegation, audited; used by every four-eyes step (publishing papers, results, merit lists, disqualifications, envelope approvals) | 2.0 (framework), used from 2.2 on |
| C-3 | Server-authoritative timing; controlled incident and candidate-compensation process for platform-side outages | Platform incident record with affected sessions/attempts; compensation actions (time extension, re-sit eligibility, attempt voiding) only through an approved, four-eyes, audited workflow; candidates notified; runbook | Design and runbook in 2.0; mechanics in 2.5 |
| C-4 | Separate exam application with strict session, authentication, API and browser security boundaries | Separate origin and build; separate OIDC client/audience; delivery API process exposes only delivery endpoints; attempt-scoped short-lived tokens bound to device key; strict CSP, CORS, frame and referrer policies; no shared cookies or storage with the staff app | 2.0 (shell + boundaries), 2.5 |
| C-5 | AI decisions and recommendations fully auditable (model/version, suggestion, evaluator changes, final human decision) | `AiInvocation` + `AiSuggestion` + decision record linking suggestion → human change → final decision with actor and reason; immutable, in the audit chain | 2.12 (design fixed now in `10-ai-gateway.md` §5) |
| C-6 | Service identities independently governed and read-only initially | As implemented for KI-59; any write capability needs a new decision | Done; future change needs approval |
| C-7 | Real-candidate admission testing blocked until production blockers are resolved | Recorded in `runbooks/production-readiness.md`; the M1 milestone uses synthetic candidates only | Ongoing |

## Blueprint decisions P2-01…P2-20

| # | Decision | Status |
|---|---|---|
| P2-01 | Consolidate former Phases 3–8 into increments 2.0–2.15 | Approved (roadmap structure) |
| P2-02 | Candidates stored separately from students | Not yet decided (recommendation stands; needed for 2.3) |
| P2-03 | Delivery as a separate process profile of the same codebase | Not yet decided (needed for 2.0; recommendation stands) |
| P2-04 | Separate exam application on its own origin | Approved via C-4 |
| P2-05 | Candidate accounts + session access codes | Approved with C-1 requirements |
| P2-06 | New MODERATOR system role | Not yet decided (needed for 2.8) |
| P2-07 | Refine placeholder permissions | Not yet decided (needed for 2.1) |
| P2-08 | Support access through tenant-approved, time-boxed grants | Not yet decided (needed before production support) |
| P2-09 | Email notification channel | Not yet decided (proposed for 2.0; KI-63) |
| P2-10 | Payments deferred | Not yet decided (recommendation: defer) |
| P2-11 | Legal review | Approved: start immediately |
| P2-12 | Scores in webhooks only for approved endpoints | Not yet decided (needed for 2.14) |
| P2-13 | Four-eyes defaults | Approved with C-2 |
| P2-14 | Proctoring up to L3, no live video, no automated decisions | Not yet decided (needed for 2.10) |
| P2-15 | AI anchoring default and agreement gate | Not yet decided (needed for 2.12; C-5 applies) |
| P2-16 | Time keeps running during disconnection | Approved in principle with C-3 (platform outages handled by the compensation process) |
| P2-17 | ADR-0004 proof of concept scheduling | Not yet decided (needed before 2.11) |
| P2-18 | Cross-shift normalisation off by default | Not yet decided (needed for 2.6) |
| P2-19 | Browser-only delivery | Not yet decided (D-08 remains “proposed”) |
| P2-20 | LTI 1.3 and OneRoster first | Not yet decided (needed for 2.14) |

## Open design questions

| # | Question | Needed for | Recommendation |
|---|---|---|---|
| Q-01 | May the business and technical custodian of a service identity be the same person? (Currently allowed.) | Now (KI-59 follow-up) | Allow, but show a warning and require different people where the organization has ≥ 2 integration managers |
| Q-02 | Candidate MFA factors and default policy (TOTP, email one-time code; SMS needs a provider decision) | 2.0 | TOTP + email code; default “optional”, “required” selectable per assessment purpose |
| Q-03 | Candidate identity boundary: separate Keycloak realm for candidates, or a separate client in the current realm | 2.0 | Separate realm (no staff sessions, policies or brute-force settings shared with candidates) |
| Q-04 | Four-eyes in organizations with a single administrator | 2.0 | Block the step and show who can be given the approver role; never allow self-approval or platform-staff approval |
| Q-05 | Session-code delivery: released by the proctor at session start, or printed on the admit card | 2.0 design / 2.5 | Proctor-released codes for supervised sessions; per-candidate codes for remote sessions, valid only in the session window |
| Q-06 | Candidate-compensation policy for platform outages (extension formula, re-sit eligibility, approvers, notice to candidates) | 2.0 runbook / 2.5 | Extension = lost time + 5 min, re-sit if > 15 min lost or answers unrecoverable; controller + second approver; institution policy confirms |
| Q-07 | Exam application domain: one shared exam domain, or per-tenant subdomains | 2.0 | One exam domain per region (simpler certificates and CSP), tenant chosen by session code |
| Q-08 | Account recovery for candidates without email access (mobile-only) | 2.3 | Mobile verification needs an SMS provider decision; until then email is mandatory |
| Q-09 | Retention periods (proposals in `18-business-readiness.md`) | 2.3 | Await legal review |
| Q-10 | First SIS/ERP vendor scope (KI-60) | 2.14 | Product decision |
| Q-11 | Load-test harness tooling without new libraries | 2.0 | JDK `HttpClient`-based harness in `backend/devtools` (no new dependency); k6 or Gatling only if approved |
