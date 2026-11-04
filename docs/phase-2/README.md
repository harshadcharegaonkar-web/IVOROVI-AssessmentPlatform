# Phase 2 blueprint — AI-assisted college screening and assessment platform

Status: **planning only — awaiting your review and explicit approval** (2026-11-04). No Phase 2 code has been written
and no Phase 1 module has been changed for Phase 2.

## Documents

| # | Required document | File |
|---|---|---|
| 1 | Product requirements | `01-product-requirements.md` |
| 2 | Functional module breakdown | `02-functional-modules.md` |
| 3 | End-to-end user workflows (with role matrix) | `03-user-workflows.md` |
| 4 | Updated domain model | `04-domain-model.md` |
| 5 | Assessment engine architecture | `05-assessment-engine.md` |
| 6 | Question bank architecture | `06-question-bank.md` |
| 7 | Examination delivery architecture (incl. online exam security, coding isolation) | `07-examination-delivery.md` |
| 8 | Evaluation, moderation and results architecture | `08-evaluation-and-moderation.md` |
| 9 | Proctoring architecture | `09-proctoring.md` |
| 10 | AI gateway and AI evaluation architecture (governance) | `10-ai-gateway.md` |
| 11 | Integration architecture | `11-integration-architecture.md` |
| 12 | Security and privacy assessment | `12-security-and-privacy.md` |
| 13 | Updated permission catalogue and role matrix | `13-permission-catalogue.md` |
| 14 | API design | `14-api-design.md` |
| 15 | Database design and migration strategy | `15-database-and-migration.md` |
| 16 | Deployment architecture (SaaS, private cloud, on-premises) | `16-deployment-architecture.md` |
| — | Delivery roadmap, estimates, risks, test strategy, demonstrations | `17-roadmap.md` |
| — | Business readiness | `18-business-readiness.md` |

## Relationship to the existing plan

`architecture/03-development-plan.md` split this work into Phases 3–8. This blueprint consolidates it as **Phase 2
increments 2.0–2.15**, keeps its first milestone (admission entrance test with merit list) and its accepted decisions
(ADR-0001 modular monolith, ADR-0003 RLS, ADR-0004 code execution, D-05 vision worker, D-06 LLM adapters, D-08
browser-only), and changes nothing in completed Phase 1 modules except additive extensions listed per increment.

## Recommended execution sequence

1. Close Increment 1.7 by implementing KI-59 (`architecture/10-api-client-authorization.md`) after your approval of
   D-KI59-1…6.
2. Approve this blueprint's decisions below.
3. 2.0 Foundations → 2.1 Item bank → 2.2 Authoring → 2.3 Candidates/admissions (parallel with 2.1–2.2) → 2.4 Scheduling
   → 2.5 Delivery (load/chaos gate) → 2.6 Results → 2.7 Merit lists — **milestone M1** (synthetic admission test).
4. In parallel with 2.0–2.7: close release blockers RB-1, RB-2/RB-3, RB-5 (incl. minors' consent), schedule RB-6.
5. Then 2.8 Manual evaluation → 2.9 Academic assessments → 2.10 Proctoring L2/L3 → 2.11 Coding (after ADR-0004 PoC) →
   2.12 AI → 2.13 Placement → 2.14 Integrations → 2.15 Licensing and hardening.

## Decisions requiring your approval

| # | Decision | Recommendation |
|---|---|---|
| P2-01 | Consolidate former Phases 3–8 into Phase 2 increments 2.0–2.15 | Approve |
| P2-02 | Candidates are separate records from students; conversion on admission reuses 1.5 services | Separate |
| P2-03 | Examination delivery: same codebase, separate process profile (not a separate microservice) | Process profile |
| P2-04 | Candidate exam client as a separate minimal SPA on its own origin | Separate SPA |
| P2-05 | Candidate sign-in: Keycloak candidate accounts (verified email/mobile) for applicants; session access code for exam entry; walk-in access-code-only mode for centre tests | Accounts + access code |
| P2-06 | New system role MODERATOR | Add |
| P2-07 | Replace coarse placeholder permissions with the refined catalogue (never granted, so no migration impact) | Replace |
| P2-08 | Platform support access to tenant data only through tenant-approved, time-boxed, audited grants | Approve |
| P2-09 | Enable email as a notification channel (admit cards, results, incidents) | Enable (opt-out per category) |
| P2-10 | Payments for application fees | Defer until a provider is chosen |
| P2-11 | Legal review: DPDP for minors (parental consent), proctoring consent, recruiter sharing, retention schedule | Start now (blocks real candidates) |
| P2-12 | Webhooks may include scores only for endpoints explicitly approved for result data | Approve |
| P2-13 | Four-eyes approval on by default for admission and semester results, merit lists and disqualifications | On |
| P2-14 | Proctoring up to L3 (AI-assisted review) in Phase 2; no live video, no automated decisions | Approve |
| P2-15 | AI marking: suggestion shown after the evaluator's own mark by default; agreement gate QWK ≥ 0.8 | Approve |
| P2-16 | Exam time keeps running during candidate disconnection; proctor-approved allowance only | Approve |
| P2-17 | Schedule the ADR-0004 proof of concept during 2.5–2.8 (gate for coding assessments) | Approve |
| P2-18 | Cross-shift normalisation available but off by default; method documented per event | Approve |
| P2-19 | Browser-only delivery (no lockdown browser) in Phase 2 | Keep D-08 |
| P2-20 | First external connectors: LTI 1.3 and OneRoster; vendor SIS connectors only on approved scope | Approve |
| D-KI59-1…6 | API-client authorization model, service identities, owner-suspension workflow | See `architecture/10` |

## Production readiness

Not claimed. Open release blockers (RB-1 scanner, RB-2/RB-3 data-key management and rotation tooling, RB-4/KI-46, RB-5
DPDP review and rights workflow/KI-48, RB-6 penetration test) remain in `runbooks/production-readiness.md`; Phase 2 adds
gates (delivery load/chaos tests, ADR-0004 PoC, AI quality gate, proctoring fairness evaluation).
