# Increment 2.2 MVP — assessment creation and publication (scope reduction and delivery record)

Status: **superseded 2026-11-08** by `seven-day-replan.md` (product owner asked for a full-platform replan before implementation). Kept as the reuse inventory and MVP specification; not being implemented as a separate release. Original status: authorized (MVP scope only). Target: seven calendar days from 2026-11-08 (day 1)
to 2026-11-14 (day 7). This document replaces `increment-2.2-plan.md` for delivery. The full plan stays as the design
reference for the deferred capabilities. **No production-readiness claim.**

## 1. Inspection: what already exists and is reused (not rebuilt)

| Capability needed | Existing component (increment) | Reuse in the MVP |
|---|---|---|
| Approved questions, search, filters (subject, topic, difficulty) | `QuestionService.search` with `approvedOnly`, bank access checks `QuestionAccess` (2.1) | New read-only module API `ApprovedQuestions` in the question module wraps them. No change to 2.1 behaviour |
| Candidate view of a question without the key | `QuestionType.deliveryProjection`, content format v1 (2.1) | Paper preview renders through it |
| Question rendering (math, images, options) | `@ivorovi/content` and `QuestionShared` (`Content`, `OptionsView`, image loader) (2.1) | Assessment preview and picker |
| Permanent references to question versions | `QuestionReferences` + `question_version_reference` (2.1) | Every question version in a published assessment (fixed and pool) is referenced at publication, so it can never be deleted |
| Review bound to content, invalidation, DB-enforced approval policy | 2.1 governance pattern (review hash, triggers, policy snapshot) | Same pattern for assessment versions (new tables, same design) |
| Immutability guard | 2.1 allowlist trigger pattern | Same pattern |
| Privileged permissions with four-eyes assignment | P2-08 (`Permission.PRIVILEGED`, `privileged_permissions()`, `PrivilegedRoleAssignments`) | `assessment.publish` is added to the privileged set (Q-2.2-1); no new mechanism |
| Four-eyes framework | `ApprovalService` (2.0) | Used by the deferred policy relaxation; not needed for MVP publication (a reviewer and a publisher are already independent people) |
| Audit trail and history | `AuditLog` (hash-chained), history query pattern (2.1) | All assessment actions audited; history tab |
| Tenant isolation | RLS loop, `TenantContext`, `TenantIsolationIT` (Phase 0/1) | All new tables under RLS; covered automatically |
| Encryption of secrets at rest | `FieldCipher` (1.5) | Paper seeds are stored encrypted |
| Outbox events | `Outbox` (1.0/2.0) | `assessment.published` |
| Idempotency, rate limits, error format | Phase 1 platform | Unchanged |
| Staff UI kit, routing, guards, unit pickers | `@ivorovi/ui`, `UnitOptions`, `RequirePermission` (1.x) | All screens |
| E2E harness, Keycloak dev users | 1.x/2.1 | Faculty, institution admin and exam controller (three people) demonstrate admission governance |

## 2. Dependency-based scope

Order: each step only needs the ones above it.

| Step | Delivers (MVP items) | Depends on |
|---|---|---|
| A. Permissions and decisions | `assessment.read/author/review/publish`; publish privileged; role grants (Q-2.2-1, Q-2.2-6) | P2-08 |
| B. Data model and DB guards | assessment, version, items, rules, frozen pools, reviews, transitions, papers, permanent version references; immutability, draft-only children, approved-question check, publication policy (items 1, 13, 15) | A, 2.1 tables |
| C. Configuration and selection | purpose, name, description, duration, total marks, instructions (2, 3); manual selection of approved versions with subject/topic/difficulty filters (4, 5, 6); random rules and a difficulty distribution (7, 8); marks and negative marking (9) | B, `ApprovedQuestions` |
| D. Validation | totals, pool sizes, exposure rule (Q-2.2-5), admission banks only, duplicates, still-approved check (10) | C |
| E. Papers and preview | secure seed (encrypted), deterministic generation, stored question versions and option order, hash; candidate-view preview without keys (11) | C, D |
| F. Workflow | submit, review, approve-and-publish with policy per purpose (Q-2.2-2), new version, retired-question warning (Q-2.2-3) (12, 13, 14) | B–E |
| G. UI | assessments list, create, editor (settings, questions, random rules, validation), preview, detail/workflow | A–F |
| H. Evidence | unit, integration, security, E2E, CI, demo | all |

## 3. MVP scope versus deferred

| In the MVP | Deferred (model prepared, no redesign needed) |
|---|---|
| Purposes ADMISSION, INTERNAL, SEMESTER, PLACEMENT (model also accepts APTITUDE and PRACTICE) | Templates; purpose presets beyond governance and defaults |
| One question list per assessment (`section_no` column = 1) | Multiple sections, section timing, navigation modes |
| Random rules: bank + subject/topic/difficulty filter + count + marks; "difficulty distribution" creates one rule per difficulty | Multi-dimensional blueprint targets and guaranteed/not-guaranteed classification |
| Feasibility: per-rule pool ≥ count, rules in one bank must not overlap (or the union must cover the sum), generation retried on dead ends | Matching-based feasibility proof |
| Scoring: marks and negative marks per question (question default or override); partial credit as defined by each question type, unchanged | Fractional negative marking, floors, scoring preview |
| Seeded generation with option shuffling, stored papers (PREVIEW now; ATTEMPT from 2.5 through the same service) | Generator optimizations, statistical exposure analysis |
| Review → approve-and-publish (one action by an independent publisher); states DRAFT, IN_REVIEW, PUBLISHED, SUPERSEDED; APPROVED and WITHDRAWN kept in the model | Separate approve step and withdraw action, policy relaxation through four-eyes for SEMESTER/PLACEMENT/APTITUDE (until then they are always high-stakes, which is stricter) |
| Retired or superseded question in a published assessment: administrative warning, published version unchanged, new version required for changes | Item cancellation in results (2.6) |
| — | AI generation and evaluation, analytics, adaptive testing |

## 4. Non-negotiable requirements and how the MVP meets them

| Requirement | Mechanism |
|---|---|
| Published versions immutable | DB allowlist trigger on versions; items, rules and pools writable only while DRAFT; pools write-once |
| References to immutable approved question-version ids | `assessment_item.question_version_id` and pool rows checked as APPROVED (same tenant) when written; re-checked at submission and publication; question references recorded at publication |
| Sessions keep their version | Versions immutable and never deleted; `AssessmentReferences` (permanent references, as for questions) is the hook 2.4 uses; tested |
| No answer keys for unauthorized users | Assessment APIs never return keys; preview uses the candidate projection; leak tests |
| RLS and tenant isolation | All new tables under RLS; isolation tests |
| Independent approval for publication; `assessment.publish` separate and privileged | DB and service: publisher ≠ author; ADMISSION/SEMESTER/PLACEMENT/APTITUDE also publisher ≠ reviewer and reviewer ≠ author (three people); privileged-role guard extended |
| Reproducible and auditable random selection; stored papers; protected seeds | 256-bit SecureRandom seed per paper, stored encrypted; HMAC-SHA-256 deterministic stream; papers store question-version ids, option order, generator version and hash; immutable; regeneration from the stored seed reproduces the hash; the seed itself is returned only to holders of `assessment.publish` through an audited endpoint |
| Retired questions never silently alter published assessments | Frozen pools and fixed items; a warning lists affected questions; changes need a new version |

## 5. Decisions recorded (2026-11-08)

Q-2.2-1 to Q-2.2-6 are as decided by the product owner (decision register). KI-79: the targeted fix is approved. KI-75:
a focused fix is to be prepared, and must not delay the MVP unless picker performance is unacceptable.

## 6. Daily log

| Day | Date | Work | Hours |
|---|---|---|---|
| 1 | 2026-11-08 | Inspection, scope proposal, decisions; KI-79 fix; MVP backend | see §7 |

## 7. Results

To be completed at the MVP demonstration.
