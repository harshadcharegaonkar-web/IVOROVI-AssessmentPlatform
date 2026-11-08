# 17 — Delivery roadmap and execution sequence

Estimates are in **engineering weeks for a team of 2–3 engineers plus QA support**, including tests, docs, demo data and
CI, at the quality bar of Phase 1. They are planning ranges, not commitments; each increment is re-estimated when its
detailed design is approved. Every increment ends with your review and explicit approval (same gate as Phase 1).

## Sequence

| Inc. | Scope | Depends on | Key architecture decisions | Acceptance criteria (summary) | Est. |
|---|---|---|---|---|---|
| **2.0 Foundations** | Close 1.7 (KI-59); outbox throughput (KI-64) and scheduled-job leases (KI-61); email channel (P2-09); candidate identity client in Keycloak; `delivery`/`worker` process profiles; `apps/exam` shell on separate origin; licensing/entitlement skeleton; load and chaos test harness | Phase 1 | P2-03, P2-04, P2-05, P2-09 | Outbox ≥ 2,000 events/s; harness drives 2,000 scripted clients; no Phase 1 regression | 2–3 |
| **2.1 Item bank** | Banks, taxonomy, objective item types (single, multiple, true/false, numeric, short text, fill-in-the-blank, matching, ordering), versions, review workflow, secure banks, CSV import, media with scanning | 2.0 | P2-07 | Author → review → approve; answer keys encrypted; secure-bank access audited; import report | 3 |
| **2.2 Assessment authoring** | Assessment definitions and versions, sections, selection rules, marking schemes, timing/navigation, purpose presets, publication validation | 2.1 | — | Random-rule blueprint validated against bank; published version immutable; four-eyes publish | 2 |
| **2.3 Candidates and admission cycle (minimal)** | Candidate records + dedup, cycle, offered programs, form builder (field library), public application portal, eligibility rules, document upload (quarantine), application review | 2.0 | P2-02, P2-11 | Candidate applies, eligibility evaluated, officer verifies; DPDP notices captured | 3–4 |
| **2.4 Scheduling** | Events, sessions (fixed/window), venues/rooms, allocation (bulk async), accommodations, access codes, admit cards | 2.2, 2.3 | — | 10,000 allocations in < 2 min; admit cards generated; accommodations reflected in deadlines | 2 |
| **2.5 Delivery engine** | Paper generation/sealing, attempt lifecycle, server timing, autosave journal + offline queue, resume, idempotent submit, auto-submit, integrity L1, proctor dashboard (admit, monitor, extend, incidents) | 2.4 | P2-14, P2-16 | **Gate:** 2,000 concurrent scripted candidates, p95 save < 300 ms, zero lost acknowledged answers under chaos (pod kill, network drop, Valkey restart); answer-key leakage tests; answer-persistence requirements 1–7 (`07-examination-delivery.md`); prerequisite: work package R (layered rate limiting, outage detection beyond total failure) | 4–5 |
| **2.6 Auto-scoring and results** | Scoring per type, negative/partial marks, result computation, optional shift normalisation, ranks/tie-breaks, approval/publication, scorecards, candidate result view, result queries | 2.5 | P2-13, P2-18 | Recomputation reproduces results exactly; four-eyes publish; scorecard verification hash | 2–3 |
| **2.7 Merit lists, offers, conversion** | Category merit lists, cut-offs, offers and acceptance, conversion to 1.5 students and enrolment | 2.6 | — | **Milestone M1:** synthetic admission test end to end with published merit list | 2 |
| 2.8 Manual evaluation and moderation | Essay/upload types, rubrics, anonymised allocation, double/third marking, moderation, re-evaluation | 2.6 | — | Two evaluators + moderator flow; tolerance rules; full score history | 3 |
| 2.9 Academic assessments | Internal and semester purposes, academics components, weightage and grade computation | 2.8 | — | Internal + semester assessment feed grades for a batch | 2 |
| 2.10 Proctoring L2/L3 | Snapshots, vision worker, flags with confidence, review queue, incidents, decisions, appeals, fairness evaluation | 2.5 | P2-14 | No automated decisions; fairness report before enabling L3 | 3–4 |
| 2.11 Coding assessments | ADR-0004 PoC (gate) → code-runner, language profiles, coding item type, sample/hidden tests, verdicts, similarity check | 2.5, 2.8 | P2-17 | PoC passes escape/limit tests; 1,000 submissions burst; no code in API process | 4 |
| 2.12 AI gateway and assistance | Providers per tenant, use-case policies, templates, AI drafts, evaluation suggestions with quality gate, analytics narratives, recommendations, suspicious-activity signals | 2.8, 2.10 | P2-15 | Off by default; agreement gate; full invocation audit; local-model option demonstrated | 3–4 |
| 2.13 Placement and employability | Companies, drives, eligibility, employability templates, shortlists, recruiter portal, consent | 2.6 (2.11 for coding) | P2-11 | Recruiter sees only consented shortlisted candidates of their drive | 3 |
| 2.14 Institutional integrations | SSO brokering, LTI 1.3 (launch + grade passback), OneRoster import, mapping layer, new webhook events | 2.6 | P2-12, P2-20 | LMS launch and grade passback with a reference LMS; SSO login | 3 |
| 2.15 Licensing and hardening | Plans/entitlements/metering, on-prem bundle (Helm + air-gapped), DR drills, DAST, pen-test preparation | all | — | Install on all three topologies; restore drill; no open critical/high findings | 3 |

Indicative total: **45–55 engineering weeks**; milestone M1 after increments 2.0–2.7 (**≈ 20–24 weeks**).

## Minimum reusable capabilities before the first admission assessment

1. Candidate identity, application and eligibility (2.3).
2. Item bank with objective types and review (2.1).
3. Assessment authoring with random selection and publication (2.2).
4. Scheduling, allocation, accommodations, admit cards (2.4).
5. Delivery engine with server timing, autosave, resume, idempotent submission, L1 integrity and proctor dashboard (2.5).
6. Auto-scoring, results, approval and publication, scorecards (2.6).
7. Merit lists and offers (2.7).
8. Cross-cutting: email notifications, audit, load/chaos-tested capacity, exam-day runbooks.

**For real candidates (not just synthetic):** RB-1 scanner, RB-2/RB-3 key management, RB-5 DPDP review incl. minors'
consent, RB-6 penetration test, and a dress rehearsal with the institution. Manual evaluation, proctoring L2/L3, coding
and AI are **not** required for the first (objective) admission test.

## Test strategy (every increment)

Unit tests for policies and type handlers; Testcontainers integration tests incl. cross-tenant and authorization
matrix; contract tests for the generated API client; Playwright E2E with axe on desktop/tablet/mobile; load and chaos
tests from 2.5 on; security tests (answer-key leakage, replay, time manipulation, sandbox escape for 2.11); synthetic
demo data per increment (separate demo organization, never test data); performance benchmarks recorded in
`docs/performance/`.

## Demonstration requirements

Each increment ships a scripted demonstration on the Meridian demo organization: e.g. 2.5 — 200 scripted candidates
plus one human candidate on a phone, with a forced disconnection and resume; 2.7 — full admission cycle from application
to offer acceptance; 2.10 — reviewer dismissing and escalating flags; 2.12 — AI suggestion accepted/edited with audit.

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Exam-day load or outage | candidates lose time/answers; reputational damage | load/chaos gates, offline queue, pre-scaling, freeze, rehearsal, incident procedure with deadline extension |
| Question paper leak | test invalidated | secure banks, blind assembly, late paper release, audit, watermarking |
| Proctoring false positives / bias | unfair outcomes, legal exposure | human decisions only, fairness evaluation, conservative thresholds, appeals |
| AI evaluation errors | wrong marks | suggestions only, agreement gate, sampling, anchoring control |
| Sandbox escape (coding) | infrastructure compromise | ADR-0004 isolation, PoC gate, separate zone, patch cadence |
| Legal (DPDP, minors, proctoring consent) | blocks launch | early legal review (P2-11), consent flows designed in 2.3 |
| Scope creep across purposes | delays M1 | engine-first sequence; purposes are presets only |
| Release blockers RB-1…RB-6 remain open | no production use | tracked in the readiness register; scheduled alongside 2.0–2.7 |
