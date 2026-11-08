# Phase 2 decision register

Updated 2026-11-04 after the product owner's “KI-59 Approval and Phase 2 Blueprint Decisions” message. Status values:
**Approved**, **Approved with conditions**, **Not yet decided** (recommendation stands, to be confirmed when the
increment that needs it is planned), **Open question** (needs an answer before the named increment starts).

## Authorizations in force

| Scope | Status |
|---|---|
| KI-59 implementation | Approved, implemented; Increment 1.7 closed (commit `fe68a41`, CI run 41777630761) |
| Phase 2 shared assessment engine | **Approved with conditions C-1…C-7** |
| Phase 2 roadmap structure (increments 2.0–2.15) | **Approved with conditions C-1…C-7** |
| Increment 1.7 | **Formally accepted** 2026-11-04 (CI run 41777630761) |
| Increment 2.0 | **Authorized** 2026-11-04: parts A–F as planned; 25–29 days is a planning estimate, not a commitment. Extra acceptance requirements in `increment-2.0-plan.md` §7a. **Implemented; awaiting acceptance** (record: `increment-2.0.md`) |
| Increment 2.0 | **Conditionally accepted** 2026-11-08 (CI run 42444423260); conditions A–D in `increment-2.0-follow-ups.md` |
| Increment 2.1 (question bank) | **Authorized** 2026-11-08; plan revised the same day with the product owner's modifications and **awaiting final review before implementation** (`increment-2.1-plan.md`) |
| Increment 2.2 and later | Not authorized (separate approval needed) |
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
| P2-03 | Exam delivery runs as a separate process from the administrative application | **Approved** 2026-11-04 |
| P2-04 | Separate exam application on its own origin | Approved via C-4 |
| P2-05 | Candidate accounts + session access codes | Approved with C-1 requirements |
| P2-06 | New MODERATOR system role | Not yet decided (needed for 2.8) |
| P2-07 | Refine placeholder permissions | **Decided** 2026-11-08: distinct permissions for exam administration, candidate check-in, incident management, compensation proposal and compensation approval; no broad exam permissions where not needed. Question bank: distinct read, author, review, approve and bank-management permissions (2.1). The exam split is applied in work package R (it changes 2.0 grants) |
| P2-08 | Support access through tenant-approved, time-boxed grants | Not yet decided (needed before production support) |
| P2-09 | Email notifications | **Approved** 2026-11-04, implemented in 2.0 |
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

## Implementation notes from Increment 2.0 (not new product decisions)

These are technical choices made inside the authorized scope; none of them decides an open question below.

| # | Note | Why |
|---|---|---|
| N-2.0-1 | Candidate password reset requires the second factor (authenticator code or recovery code) instead of Keycloak's default, which removes the authenticator after an email link | C-1: an email mailbox alone must not defeat MFA. Lost phone and codes → audited institution reset |
| N-2.0-2 | After a password reset every candidate session ends (enforced by the platform from the realm's events) | C-1 "recovery invalidates sessions"; Keycloak only does it when the candidate ticks a box (KI-67) |
| N-2.0-3 | Readiness check-in sessions are the 2.0 vehicle for session codes, presence and compensation | No exam business logic in 2.0; real exam sessions replace them in 2.5 |
| N-2.0-4 | Compensation entitlements are codes bound to a candidate, used from the signed-in portal; emails carry no code | Nothing secret is sent by email |
| N-2.0-5 | Session-code limits: 10/min per candidate, 5 failed/min per code, 300/min per address (configurable) | Campus NAT (KI-66) |
| N-2.0-6 | The staff screen *suggests* "lost time + 5 min" for extensions; nothing is applied without four-eyes approval | Superseded by the Q-06 decision (configurable policy, delivered with B/2.5) |
| N-2.0-8 | Three permissions became available for the 2.0 foundations: `exam.session.manage`, `exam.incident.manage`, `exam.incident.decide` (granted to ORG_ADMIN, INSTITUTION_ADMIN, EXAM_CONTROLLER). The placeholders `exam.read`/`exam.manage` stay PLANNED | Needed by parts B–D. Under P2-07 (decided 2026-11-08) these become: exam administration (`exam.session.manage`), candidate check-in (`exam.checkin.manage`, new), incident management (`exam.incident.manage`), compensation proposal (`exam.compensation.propose`, new) and compensation approval (`exam.compensation.approve`, replacing `exam.incident.decide`); role grants narrowed (ORG_ADMIN/INSTITUTION_ADMIN lose compensation approval by default). Migration in work package R |
| N-2.0-7 | Codes can be printed per candidate or released by the invigilator | Confirmed by the Q-05 decision |

## Approvals for Increment 2.1

| # | Item | Status |
|---|---|---|
| L-2.1-1 | New frontend library **KaTeX 0.16 (MIT)** for mathematical notation, MathML output only, `trust: false` (plan §6.3, ADR-0010 proposal) | **Approved** 2026-11-08 (final 2.1 approval): MathML only, no HTML output; links, images, HTML commands and unsafe extensions disabled; pinned (0.16.47) and self-hosted, no CDN; exam CSP preserved; no new CSP exception for KaTeX without approval; cross-browser, accessibility and screen-reader testing; documented fallback |
| L-2.1-2 | Separate budget for the KaTeX chunk: **≤ 80 kB gzip** | **Approved** 2026-11-08 with conditions: KaTeX stays lazy-loaded, never in the main bundle, loaded only where mathematics is rendered; main application budget 180 kB and exam application budget 120 kB unchanged; KaTeX size tracked independently (budget script, named chunk); any increase beyond 80 kB needs separate approval |

## Increment 2.1 decisions

| # | Decision | Status |
|---|---|---|
| P2-08 | Conflict between the 1.3 privilege ceiling and the P2-07 question matrix (KI-74). **Option 2:** administrators delegate privileged roles through independent approval. ORG_ADMIN and INSTITUTION_ADMIN hold `question.read`, `question.author`, `question.review` and `question.bank.manage`, never `question.approve` or `question.key.read` by default. Assigning a role with a privileged permission opens a four-eyes request in the 2.0 framework. The approver must be an existing holder of the privileged permissions at the scope, never the requester or the target. Checks are live at approval and activation, a revoked approver invalidates the request, and enforcement is in the service and the database. The first holder is bootstrapped by a platform operator with a ticket reference, only when no holder exists, and audited. The 1.3 ceiling is otherwise unchanged. Design: 2.1 plan §10–10.1 | **Decided** 2026-11-08; +3–5 days accepted as a planning estimate |

## Open design questions

| # | Question | Needed for | Recommendation |
|---|---|---|---|
| Q-01 | Same person as business and technical custodian? | — | **Decided 2026-11-04: two different people are mandatory**, enforced in the service and by a database check constraint (`service_identity_distinct_custodians`) |
| Q-02 | Candidate MFA and verification | — | **Decided 2026-11-04:** verified email, expiring single-use session codes and optional TOTP authenticator-app MFA; SMS never the only MFA mechanism |
| Q-03 | Candidate authentication separate from staff | — | **Decided 2026-11-04:** separate, including session and recovery boundaries (separate realm) |
| Q-04 | Four-eyes with a single administrator | — | **Decided 2026-11-04:** never bypassed; an authorized second approver or an approved higher-level escalation decides, otherwise the request stays pending |
| Q-05 | Session-code delivery | — | **Decided 2026-11-08: both** printed (per-candidate) session codes and invigilator-controlled code release are supported |
| Q-06 | Candidate-compensation policy | — | **Decided 2026-11-08:** configurable per organization. Default: restore verified lost time where possible, plus a configurable recovery buffer, and support rescheduling when required. Independent approval and a full audit trail are mandatory. Implemented with B (verified lost time) and 2.5 |
| Q-07 | Exam application domain | — | **Decided 2026-11-04:** one shared exam domain separate from the administrative portal; tenant context resolved from trusted server-side configuration; institution-specific domains designed for later |
| Q-08 | Account recovery for candidates without email access (mobile-only) | 2.3 | Mobile verification needs an SMS provider decision; until then email is mandatory |
| Q-09 | Retention periods (proposals in `18-business-readiness.md`) | 2.3 | Await legal review |
| Q-10 | First SIS/ERP vendor scope (KI-60) | 2.14 | Product decision |
| Q-11 | Load-test tooling | — | **Decided 2026-11-04:** no new production dependencies; existing tools or a lightweight standalone harness with repeatable results |
