# 03 — End-to-end user workflows

Each workflow lists the actor, the scope it acts in, and the consequential steps that are audited (⚑) or need a second
person (✌, four-eyes; configurable per tenant, default on for admissions and semester results).

## W1 — Admission entrance assessment (milestone M1)

| # | Actor (scope) | Step |
|---|---|---|
| 1 | Admissions officer (college) | Creates admission cycle 2027: programs offered, intake, categories, important dates ⚑ |
| 2 | Admissions officer | Builds the application form from field library (personal, academic history, category, documents); sets eligibility rules (e.g. 12th-grade PCM ≥ 60%, age) ⚑ |
| 3 | Candidate (public) | Registers (email/mobile verification), fills application, uploads documents (quarantine + scan, RB-1), submits; receives application number |
| 4 | System | Evaluates eligibility rules; flags documents for verification |
| 5 | Admissions officer | Verifies documents (sample or all), resolves flags ⚑ |
| 6 | Assessment designer (college) | Builds the entrance test from the item bank: sections (Physics, Chemistry, Maths, Aptitude), random selection rules, negative marking; submits for review |
| 7 | Exam controller (college) | Reviews and publishes the assessment version ✌ ⚑; schedules event with sessions (two shifts), allocates eligible candidates, sets integrity profile; admit cards issued |
| 8 | Candidate | Downloads admit card; on exam day signs in with access code, completes identity check, takes the test (autosave, resume) and submits |
| 9 | Proctor (session) | Admits candidates, watches session dashboard, records incidents ⚑, reviews flags after the session |
| 10 | System | Auto-scores objective items; manual queues created only if subjective items exist |
| 11 | Exam controller | Decides incidents (no action / warning / score withheld / disqualification proposal) ⚑ ✌ for disqualification |
| 12 | Exam controller | Computes results (normalisation across shifts if configured), reviews statistics, approves ✌ ⚑ |
| 13 | Admissions officer | Generates merit lists per program and category with tie-breakers; publishes ✌ ⚑; issues offers |
| 14 | Candidate | Views scorecard and rank; accepts offer; uploads remaining documents |
| 15 | Admissions officer | Converts accepted candidates to students (Phase 1 student records, enrolment in batch) ⚑ |

## W2 — Internal assessment / semester examination

Faculty (subject offering scope) authors or selects questions → assessment designer/HoD review → exam controller
schedules for a batch (students from 1.5 enrolments) → students take it online or marks are entered for offline papers →
auto + manual evaluation, double marking for semester exams, moderation → results approved ✌ → **academics** consumes
the component scores (weightage, grade computation) → students see marks and grades.

## W3 — Manual evaluation and moderation

Exam controller defines the evaluation plan (anonymised, double marking with tolerance 10%, moderation sample 10%) →
system allocates scripts to evaluators (load-balanced, conflict-of-interest exclusions) → evaluators mark per rubric
criterion with annotations; optional AI suggestion shown *after* the evaluator opens the response (configurable to avoid
anchoring) → discrepancies beyond tolerance go to a third evaluator or moderator → moderator samples, adjusts with reason
⚑ → controller locks marks ⚑.

## W4 — Integrity review

Signals and snapshots stream during the attempt → vision worker produces flags with confidence → after the session the
proctor reviews a timeline per candidate, dismisses or escalates each flag with a note ⚑ → escalated flags become an
incident → candidate is notified and may respond within N days → exam controller decides ✌ ⚑. **A flag is never a
finding**; only a human decision changes a candidate's outcome.

## W5 — AI-assisted authoring

Designer requests drafts (topic, difficulty, type, count) → AI gateway calls the tenant's approved provider with a
versioned template → drafts land in the item bank as **DRAFT, AI-generated** with provenance → a human reviewer edits and
approves each item ⚑ → only approved items are selectable.

## W6 — Placement drive

Placement officer creates company and drive (eligibility: branch, CGPA, backlogs) → eligible students opt in (consent to
share scorecards with the recruiter) → employability assessment (aptitude + technical + coding) from the shared engine →
results publish → shortlist rules (cut-offs, top N) → officer reviews and releases shortlist ⚑ → recruiter (external,
drive-scoped access) sees shortlisted candidates and consented scorecards only → offers recorded.

## W7 — Coding assessment

Designer creates a coding item: statement, languages, limits, sample and hidden tests → candidate edits in the browser
editor, runs against sample tests (rate-limited) → final submission queued to `code-runner` (isolated sandbox, no network)
→ verdicts per test and score return asynchronously → hidden test details never shown to candidates → evaluators may add
manual quality marks.

## Role × workflow matrix (who participates)

| Role | W1 | W2 | W3 | W4 | W5 | W6 | W7 |
|---|---|---|---|---|---|---|---|
| Platform super admin | — (operates platform only) | — | — | — | provider setup | — | runner ops |
| Organization admin | configures modules, AI policy | ✓ | — | policy | policy | ✓ | ✓ |
| University / college admin | approves within unit | ✓ | — | ✓ | — | ✓ | — |
| Admissions officer | ✓ owner | — | — | — | — | — | — |
| Exam controller | ✓ | ✓ owner | ✓ | ✓ decides | — | ✓ | ✓ |
| Faculty | — | ✓ | ✓ | — | ✓ | — | ✓ |
| Assessment designer | ✓ | ✓ | — | — | ✓ owner | ✓ | ✓ |
| Evaluator | ✓ (subjective) | ✓ | ✓ owner | — | — | ✓ | ✓ |
| Moderator | — | ✓ | ✓ | — | — | — | — |
| Proctor | ✓ | ✓ | — | ✓ owner | — | ✓ | — |
| Placement officer | — | — | — | — | — | ✓ owner | — |
| Student / candidate | ✓ | ✓ | — | responds | — | ✓ | ✓ |
| External recruiter | — | — | — | — | — | ✓ (drive only) | — |
