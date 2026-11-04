# 01 — Phase 2 product requirements

Status: blueprint for approval (2026-11-04). Planning only; no Phase 2 code exists.

## 1. Vision and goal

Evolve Ivorovi from an institutional management platform into an **enterprise, AI-assisted college screening and
assessment platform**: one system in which an organization admits candidates through entrance assessments, runs internal
and semester examinations, evaluates and moderates answers, publishes results and rankings, protects examination
integrity, and assesses students for placement — all on **one reusable assessment engine**.

**First business milestone (unchanged from the Phase 0 plan):** *an institution runs an admission entrance test and
publishes a merit list.* Phase 2 is sequenced so that this milestone is reached first, on capabilities that every later
workflow reuses.

## 2. Users

| Role | Primary goals in Phase 2 |
|---|---|
| Platform super administrator | Operate tenants, plans/entitlements, AI providers, platform health; never sees tenant assessment content by default |
| Organization administrator | Configure modules, AI policy, integrations, roles; oversee all units |
| University / college administrator | Run admissions and examinations for their unit; approve results within scope |
| Admissions officer | Configure admission cycles, applications, eligibility, screening stages, merit lists, offers |
| Exam controller | Schedule examinations, control sessions, handle incidents, approve and publish results |
| Faculty | Author questions, set internal assessments, evaluate their subjects |
| Assessment designer | Build question banks and assessment blueprints; review items |
| Evaluator | Mark allocated scripts with rubrics |
| Moderator | Moderate marks, resolve double-marking discrepancies, sample-check AI suggestions |
| Proctor | Admit candidates, verify identity, monitor sessions, review integrity flags, record incidents |
| Placement officer | Run placement drives, employability assessments, shortlists, recruiter access |
| Student / candidate | Apply, take assessments, see results and scorecards, raise queries |
| External recruiter | See shortlisted candidates and consented scorecards for their drive only |

## 3. Capabilities (scope)

| Code | Capability | Must have for Phase 2 | Later |
|---|---|---|---|
| A | Admission management and screening | Admission cycles, programs offered, application form builder (configurable fields), documents (reusing 1.5 quarantine), eligibility rules, screening stages (assessment, interview score, document verification), category/reservation-aware merit lists, offers, conversion of admitted candidates into students (1.5 records) | Application fee payments (needs provider approval), counselling rounds, seat matrix optimisation |
| B | Question bank and authoring | Item bank with taxonomy (subject, topic, outcome, Bloom level, difficulty), item types: single/multiple choice, true/false, numeric, short text, essay, file upload, matching, ordering, fill-in-the-blank, coding; item versioning, review workflow, media, import (CSV, QTI 3.0 subset), duplicate detection; assessment blueprints with sections, fixed and rule-based random selection, marking schemes, negative marking | Adaptive testing (IRT-based), item-response analytics feeding difficulty automatically |
| C | Scheduling | Assessment events, sessions (fixed time or window), candidate allocation (remote or centre/room/seat), accommodations (extra time, breaks), access codes, admit cards, reschedule and make-up sessions | Centre capacity optimiser |
| D | Online examination engine | Browser-based client, server-authoritative timing, autosave with offline queue, resume after disconnection, idempotent submission, navigation rules, section timing, calculator/scratchpad tools, accessibility (WCAG 2.1 AA), low-bandwidth mode | Native lockdown browser (D-08 keeps browser-only first) |
| E | Evaluation | Auto-scoring per item type; manual marking queues with rubrics and annotations; anonymised marking; double marking with tolerance and third marking; moderation (sample and full); re-evaluation requests; AI-suggested scores (optional) | Peer/collaborative marking |
| F | Results, rankings, scorecards | Result computation (raw, scaled, normalised, percentile), tie-break rules, category ranks, approval and publication with four-eyes, scorecards (PDF), result queries/grievances, academic grade components feeding Phase 1 academics | Statistical moderation across shifts (equi-percentile) as configurable option |
| G | Proctoring and integrity | Integrity profiles per assessment (none, basic signals, snapshots, AI-assisted review), identity verification, browser signals, webcam snapshots, AI flags with confidence, human review queue, incidents and decisions with candidate response | Live human video proctoring at scale, audio analysis |
| H | AI assistance | Optional per tenant and per use case: question generation (draft, human review), answer-evaluation suggestions with rationale, grading suggestions, performance analytics narratives, recommendations, suspicious-activity detection signals — all human-overseen | Fine-tuned institution models |
| I | Placement and employability | Companies, drives, eligibility, employability assessment templates (aptitude, technical, coding, communication), shortlists, recruiter portal with consent-based sharing, offers tracking | Interview scheduling marketplace |

## 4. Non-functional requirements

| Area | Requirement |
|---|---|
| Scale (SaaS reference) | 10,000 concurrent candidates per region, 2,000 in one event; 50 answer saves/s per 1,000 candidates; p95 save < 300 ms; submission burst of 10,000 within 2 minutes without loss |
| Availability | 99.9% monthly for the platform; 99.95% during scheduled examination windows (exam-day mode: change freeze, pre-scaling) |
| Data integrity | Zero lost acknowledged answers; every answer change journaled with sequence numbers; final submission hash-sealed |
| Security | Phase 1 controls carried forward (RLS, scoped RBAC, audit chain, field encryption); answer keys never reach the client; papers released per attempt only at start |
| Privacy | DPDP-first, GDPR-ready; purpose-bound processing; consent for proctoring and recruiter sharing; retention per data class (`18-business-readiness.md`) |
| Accessibility | WCAG 2.1 AA for candidate and staff UIs; accommodations as data, not ad-hoc |
| AI | Off by default; per-tenant provider choice incl. on-premises models; human decision for every consequential outcome |
| Portability | Same codebase for SaaS, private cloud, on-premises (incl. air-gapped without AI or with a local model) |
| Auditability | Every consequential action (publish paper, start/stop session, change score, publish result, decide incident, accept AI suggestion) in the hash-chained audit trail |

## 5. Out of scope for Phase 2

Native mobile apps, lockdown browser, live video proctoring at scale, payments (until a provider is approved),
adaptive (IRT) testing, LMS course content, fee management, timetable generation.

## 6. Success measures

* First admission entrance test run end to end on synthetic candidates with merit list published (milestone M1).
* 2,000-candidate load test passes the save/submit targets with zero lost answers.
* An internal assessment and a placement assessment reuse the same engine without new engine code (only
  configuration and outcome consumers).
* AI suggestions measured against human marking (agreement report) before any tenant can enable AI evaluation.
