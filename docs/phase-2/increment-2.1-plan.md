# Increment 2.1 — Question bank (plan)

Status: **authorized 2026-11-08** (planning and implementation). Prerequisite: Increment 2.0 conditionally accepted
(CI run 42444423260). Only 2.1 is authorized; 2.2 and later need separate approval. No AI question generation or AI
evaluation. Phase 1 and 2.0 functionality is not changed except where listed in §8.

## 1. Goal and scope

An institution-scoped bank of versioned questions with a review-and-approval workflow, ready to be referenced by
assessments from 2.2 on. In scope (as authorized):

1. Question creation, editing and versioning.
2. Single-answer and multiple-answer MCQs.
3. True/false questions.
4. Short-answer questions.
5. Categorization by subject, topic, difficulty and learning outcome.
6. Marks and negative marking.
7. Review and approval workflow.
8. States draft, reviewed, approved and retired (plus *in review* as the queue state between draft and reviewed).
9. Institution-scoped question banks.
10. Search, filters and bulk import.
11. Import validation, preview and duplicate detection.
12. Full audit history and permission enforcement.

Out of scope (later increments or not authorized): AI generation/evaluation; media (images/audio) in questions;
math rendering beyond plain text; numeric, matching, ordering and fill-in-the-blank types; secure banks with named
custodians; QTI import/export; item statistics; assessment assembly (2.2).

## 2. Architecture rules (mandatory, from the authorization) and how they are met

| Rule | Mechanism |
|---|---|
| Published (approved) versions are immutable | Database trigger rejects any change to content columns of an APPROVED or RETIRED version and any delete of a non-draft version; service never updates them; tests try direct SQL |
| Editing creates a new version | "Edit" on an approved question creates version n+1 as DRAFT (copy); the approved version stays the question's *current approved* version until n+1 is approved |
| Published assessments keep their versions | Assessments (2.2) will reference `question_version.id`, never `question.id`; versions are never deleted or rewritten, retired versions stay readable for existing references |
| Answer keys never on candidate-facing APIs | Answer key, explanation and marking scheme are stored separately, encrypted (`FieldCipher`, AAD per column and tenant); only returned to staff holding author, review or approve at the bank; a dedicated *delivery projection* type has no key fields; the delivery process does not register question endpoints (404); tests assert all three |
| Tenant and organizational isolation | RLS on every table; banks belong to an organization unit and are visible down the hierarchy to holders of `question.read` there; cross-tenant and cross-unit tests |
| Reusable across admissions, academics and placement | Questions are not tied to a purpose; banks declare intended uses (tags) only; subjects are bank taxonomy entries that *may* link to an academic subject (1.4) |
| Extensible metadata | Organization-defined attribute definitions (key, label, type, allowed values) validated server-side and stored in `attributes jsonb`; no schema change needed for new metadata |
| Every important lifecycle action audited | Audit events for bank and taxonomy changes, create, edit, new version, submit, review, request changes, approve, retire, import upload/preview/commit, answer-key views are not audited individually (staff-only, permission-gated) |

## 3. Data model (migration `V202611121000__question_bank.sql`)

```
question_bank (id, tenant_id, org_unit_id → org_unit, code, name, description, intended_uses text[]
               {ADMISSION, ACADEMIC, PLACEMENT, GENERAL}, status ACTIVE|ARCHIVED, created_by/at, updated_*, version)
question_subject (id, tenant_id, org_unit_id, code, name, academic_subject_id → subject (optional), status)
question_topic (id, tenant_id, subject_id → question_subject, parent_id → question_topic, name, status)   -- ≤ 3 levels
learning_outcome (id, tenant_id, subject_id → question_subject, code, description, bloom_level, status)
question_attribute_definition (id, tenant_id, key, label, type TEXT|NUMBER|BOOLEAN|CHOICE, allowed_values text[], required, status)

question (id, tenant_id, bank_id, current_version_no, approved_version_no NULL, status ACTIVE|RETIRED,
          created_by/at, updated_at, version)
question_version (id, tenant_id, question_id, version_no, state DRAFT|IN_REVIEW|REVIEWED|APPROVED|RETIRED|SUPERSEDED,
                  type SINGLE_CHOICE|MULTIPLE_CHOICE|TRUE_FALSE|SHORT_ANSWER,
                  stem text, options jsonb [{id, text}]           -- no correctness flags here
                  answer_key_enc text                              -- encrypted JSON (correct option ids / true|false /
                                                                      accepted answers + matching mode)
                  explanation_enc text                              -- encrypted (may reveal the answer)
                  marks numeric(6,2) > 0, negative_marks numeric(6,2) ≥ 0 ≤ marks,
                  scoring_mode ALL_OR_NOTHING|PARTIAL (multiple choice), 
                  subject_id, topic_id, difficulty VERY_EASY..VERY_HARD, outcome_ids uuid[], tags text[], attributes jsonb,
                  language, content_hash char(64), search tsvector (stem + options, generated),
                  author_id, submitted_by/at, reviewed_by/at, approved_by/at, retired_by/at, retire_reason,
                  source MANUAL|IMPORT, import_id, created_at, version)
  UNIQUE (question_id, version_no); one non-final (DRAFT/IN_REVIEW/REVIEWED) version per question
  CHECK reviewed_by <> author_id; CHECK approved_by <> author_id  (four-eyes at the database)
  TRIGGER: no content change when state IN (APPROVED, RETIRED, SUPERSEDED); no DELETE unless DRAFT
question_review (id, tenant_id, version_id, reviewer_id, decision REVIEWED|CHANGES_REQUESTED, checklist jsonb, comment, at)

question_import (id, tenant_id, bank_id, file metadata, object key, scan_status, status UPLOADED|PREVIEWED|COMMITTED|FAILED|EXPIRED,
                 counts, created_by/at, committed_at)
question_import_row (id, tenant_id, import_id, row_no, outcome VALID|INVALID|DUPLICATE_IN_FILE|DUPLICATE_IN_BANK|NEAR_DUPLICATE,
                     errors jsonb, normalized jsonb (payload without answer key) , answer_key_enc, matched_question_id, similarity)
```

* `content_hash` = SHA-256 of normalized stem + sorted normalized option texts + type (exact duplicates).
* Near duplicates: `pg_trgm` similarity on normalized stem (trusted extension, created by the migration), threshold
  0.85 within the same bank and subject; reported, never auto-merged.
* Versions referenced from 2.2 by id; `SUPERSEDED` marks an older approved version once a newer one is approved
  (still immutable and readable).

## 4. Lifecycle

```
            edit/save                submit                 review: REVIEWED              approve
  (new) ──► DRAFT ───────────────► IN_REVIEW ───────────────────────────────► REVIEWED ─────────────► APPROVED
              ▲                         │  review: CHANGES_REQUESTED              │ request changes        │
              └─────────────────────────┴─────────────────────────────────────────┘                        │
  APPROVED ──"new version"──► DRAFT (n+1)   ;  APPROVED(n) ──approve(n+1)──► SUPERSEDED   ;  any approved ──retire──► RETIRED
```

* Reviewer ≠ author; approver ≠ author (service rule + database constraint). Reviewer and approver may be the same
  person (configurable "three-eyes" per bank deferred).
* Approval requires a REVIEWED state, complete answer key, marks within limits and all required attributes.
* Retire needs a reason; retired questions are excluded from selection (2.2) but remain readable.

## 5. API design (staff API only; all under `/api/v1`)

| Method and path | Permission | Notes |
|---|---|---|
| `GET/POST /question-banks`, `GET/PUT /question-banks/{id}` | read / `question.bank.manage` | unit-scoped |
| `GET /question-banks/{id}/questions?q=&type=&state=&subject=&topic=&difficulty=&outcome=&tag=&page=` | `question.read` | full-text + filters; list never contains keys |
| `POST /question-banks/{id}/questions` | `question.author` | creates question + version 1 (DRAFT) |
| `GET /questions/{id}` | `question.read` | versions summary; key fields only with author/review/approve |
| `GET /questions/{id}/versions/{no}` | `question.read` | as above |
| `PUT /questions/{id}/draft` | `question.author` | edits the open draft (optimistic version) |
| `POST /questions/{id}/versions` | `question.author` | new draft from the approved version |
| `POST /questions/{id}/submit` · `/review` · `/approve` · `/retire` | author / review / approve / approve | state machine; four-eyes |
| `GET /questions/{id}/history` | `question.read` | audit events of the question and its versions |
| `GET /question-review-queue?stage=review|approval` | review / approve | excludes the caller's own questions |
| `GET/POST/PUT /question-taxonomy/subjects`, `/topics`, `/learning-outcomes`, `/attributes` | read / `question.bank.manage` | |
| `POST /question-banks/{id}/imports` (multipart CSV/XLSX) · `GET /question-imports/{id}` · `GET …/rows` · `POST …/commit` · `GET /question-imports/template` | `question.author` | scan gate as 1.6; preview before commit; idempotent commit |

Candidate-facing: none in 2.1. A `QuestionDeliveryView` record (stem, options, type, marks; no key, explanation or
scoring) is defined for 2.5, with a serialization test. The delivery process returns 404 for every `/question*` path.

## 6. Permission matrix (P2-07)

| Permission | Level · scope | ORG_ADMIN | INSTITUTION_ADMIN | EXAM_CONTROLLER | ASSESSMENT_DESIGNER | FACULTY | others |
|---|---|---|---|---|---|---|---|
| `question.read` — view banks and questions (no answer keys) | R · UNIT | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| `question.author` — create/edit drafts, import, submit; sees keys | W · UNIT | — | — | — | ✓ | ✓ | — |
| `question.review` — review drafts of others; sees keys | W · UNIT | — | — | ✓ | ✓ | — | — |
| `question.approve` — approve, retire; sees keys | M · UNIT | — | — | ✓ | — | — | — |
| `question.bank.manage` — banks, taxonomy, attribute definitions | M · UNIT | ✓ | ✓ | ✓ | — | — | — |

Administrators manage banks but do not see answer keys by default; custom roles can combine permissions within the
assigner's ceiling (1.3). The placeholders `assessment.read/manage` stay PLANNED for 2.2.

## 7. Screens (staff app, section "Question bank")

1. Question banks — list, create/edit bank (unit, intended uses).
2. Bank questions — search and filters (type, state, subject, topic, difficulty, outcome, tag), counts by state.
3. Question editor — type-specific editor (options with correct marks, true/false, accepted answers with matching mode),
   marks/negative marks, scoring mode, taxonomy, tags, attributes, live preview *as the candidate will see it* (no key).
4. Question detail — current version, version list and side-by-side comparison, state actions, review panel with
   checklist, audit history.
5. Review queue — "to review" and "to approve" (never own questions).
6. Taxonomy — subjects (optional link to academic subject), topics, learning outcomes, attribute definitions.
7. Import — upload, preview (valid, invalid with reasons, duplicates and near duplicates), commit, result; template
   download.

## 8. Migration strategy and Phase 1 / 2.0 impact

* One forward-only migration with new tables, RLS on every table, the `pg_trgm` extension, the four-eyes CHECKs and the
  immutability trigger; permission catalogue entries (AVAILABLE) and system-role grants per §6. No existing table is
  altered; Phase 1 data untouched.
* Code touched outside the new module: `Permission` catalogue (new entries), navigation, API client regeneration,
  bundle budget unchanged (pages lazy). Nothing in 2.0 changes; the exam-permission split from P2-07 is done in work
  package R.
* Dev demo data: a sample bank with a few questions for the walkthrough (dev profile only).

## 9. Test plan

* **Unit:** answer-key validation per type; normalization and content hash; import row parsing; state machine.
* **Integration (Testcontainers):**
  * Lifecycle and versioning, including the full DRAFT → APPROVED path and SUPERSEDED.
  * Immutability: direct SQL UPDATE/DELETE on approved versions refused by the trigger; API edits refused; a new
    version leaves the old id and content unchanged.
  * Four-eyes: author cannot review or approve via API; database CHECK refuses a direct write.
  * Permissions: each endpoint against each role, answer keys hidden without author/review/approve.
  * Tenant and unit isolation: another organization and a sibling institution see nothing (404).
  * Answer-key exposure: answer keys and explanations encrypted at rest (no plaintext in the row); list/search
    responses never contain keys; the delivery projection has no key fields; the delivery process returns 404 for
    `/api/v1/question*`; candidate tokens are rejected (401).
  * Import: template round trip; invalid rows with reasons; exact duplicates in file and in bank; near duplicates;
    commit idempotency; scan gate fail-closed outside development; row limits.
  * Search: full-text and every filter, paging.
  * Audit: every lifecycle action recorded with actor.
* **E2E (Playwright, real Keycloak):** author creates and submits each type → reviewer reviews → controller approves
  → author edits creating version 2 → version 1 unchanged → approve 2 → retire; import with preview and duplicates;
  axe on all new screens; tablet and mobile checks.
* **Regression:** full backend, frontend and E2E suites; bundle budgets.

## 10. Acceptance criteria

1. All twelve scope items work end to end in the staff app with real permissions.
2. Approved versions cannot be changed through the API or directly in the database; editing creates a new version;
   earlier version ids and content remain unchanged and readable.
3. No candidate-facing path can return an answer key, explanation or marking scheme (tests in §9).
4. Tenant and unit isolation tests pass for every endpoint.
5. Author cannot review or approve own questions (service and database).
6. Import shows a preview with row-level errors, exact and near duplicates; nothing is created before commit; commit is
   idempotent; files are scanned under the existing fail-closed rule.
7. Every lifecycle action appears in the audit trail and the question history.
8. Full backend, frontend and E2E suites green in CI; bundle budgets hold.
9. Documentation: ADR-0009 (question versioning and answer-key protection), runbook, permission catalogue, known
   issues, decision register, increment record.

## 11. Estimate

Backend 6–7 days, frontend 5–6 days, tests/E2E/docs 3 days: **14–16 engineering days**. Planning estimate, not a
commitment.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Answer keys leak through a generic serializer or log | Keys only in a dedicated encrypted column and a staff-only DTO; delivery projection type; log redaction test |
| Large banks make near-duplicate checks slow | Trigram index, same-subject restriction, limit per row; benchmark with 20,000 questions |
| Rich content later (media, math) | Stem stored as text with a format field so 2.x can add rich formats without migration of existing rows |
