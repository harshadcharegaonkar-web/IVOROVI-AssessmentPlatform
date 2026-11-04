# 02 — Functional module breakdown

Phase 2 keeps the modular monolith (ADR-0001): Spring Modulith modules with explicit published APIs, events through the
outbox, one PostgreSQL schema with RLS. Three components run as **separate deployables** because of isolation or scaling
needs: `code-runner` (ADR-0004), `vision-worker` (D-05) and `ai-worker`. The examination delivery module is part of the
monolith codebase but can run as its own process profile (`delivery`) so it scales independently on exam days
(decision P2-03).

## Modules

| Module | New/extended | Responsibilities | Depends on (published APIs) |
|---|---|---|---|
| `candidates` | new | Candidate (applicant) person records independent of students; identity documents; contact verification; dedup across cycles; conversion to student | tenancy, identity, students (on admission) |
| `admissions` | new | Admission cycles, offered programs and intake, application forms (schema-driven), eligibility rules, screening pipeline stages, merit lists, offers, acceptance, conversion | candidates, academics, assessment results |
| `itembank` | new | Items, versions, item types (SPI), taxonomy, media, review workflow, import/export (CSV, QTI), similarity detection | academics (subjects/outcomes), ai (optional generation) |
| `assessments` | new | Assessment definitions (purpose, sections, selection rules, marking scheme, timing, navigation, integrity profile, evaluation plan), validation and publication, versioning | itembank |
| `scheduling` | new | Assessment events, sessions, windows, venues/rooms/seats, allocations, accommodations, access codes, admit cards | assessments, candidates/students, tenancy |
| `delivery` | new | Paper generation and sealing, attempt lifecycle, server timer, response journal, autosave, resume, submission, auto-submit, candidate client API | scheduling, assessments |
| `integrity` | new | Integrity profiles, identity verification, client signals, snapshots, flags, review queue, incidents, decisions, appeals | delivery, ai (vision signals) |
| `evaluation` | new | Auto-scoring orchestration, manual marking tasks, rubrics, anonymisation, double/third marking, moderation, re-evaluation, AI suggestions intake | delivery, assessments, ai |
| `results` | new | Result computation (scaling, normalisation), ranks and tie-breaks, category ranks, approval/publication, scorecards, queries/grievances, outcome export to consumers | evaluation, scheduling |
| `placement` | new | Companies, drives, eligibility, employability assessment templates, shortlists, recruiter portal, consent, offers | assessments, results, students |
| `ai` (gateway) | new | Provider registry per tenant, use-case policies, prompt templates, invocation, output validation, cost/usage metering, audit of AI calls | platform |
| `coding` | new (in monolith) + `code-runner` service | Coding item type, language profiles, test cases, submission orchestration to the runner, verdict intake | itembank, delivery, evaluation |
| `academics` | extended (additive) | Assessment components per subject offering, weightage, grade computation consuming results | results |
| `students` | extended (additive) | Creation from admitted candidates (reusing 1.5 services); exam eligibility flags | admissions |
| `notifications` | extended | Email channel (approval: P2-09), templates for admit cards, results, incidents | — |
| `integrations` | extended | LTI 1.3, OneRoster, SIS mapping, recruiter access tokens, exam/result webhook events | many |
| `licensing` | new | Plans, module entitlements, metered usage (candidates, attempts, AI tokens), on-prem signed licences | platform |

## Shared engine boundary

`itembank` + `assessments` + `scheduling` + `delivery` + `evaluation` + `results` **are the assessment engine**. Business
workflows (admissions, academics, placement) are *consumers*: they create assessment events with a purpose and consume
published results through `results` outcome APIs. No business module embeds its own engine (see `05-assessment-engine.md`).
