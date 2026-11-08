# Increment 2.2 — Assessment authoring and blueprint management (plan, for approval)

Status: **plan for review, 2026-11-08. No implementation has started.** Planning was authorized by the product owner on
2026-11-08 together with the acceptance of Increment 2.1.

Scope is Increment 2.2 only. Not in scope:
- scheduling, venues and allocation (2.4);
- candidate attempts and delivery (2.5);
- score computation for real attempts and results (2.6);
- adaptive testing;
- AI generation or evaluation.

Completed increments are not changed except where §9 lists a documented dependency. The production-readiness blockers
stay open.

**Six decisions are needed with your approval (§14).** Each has a recommended option.

## Contents

1. Product requirements · 2. User workflows · 3. Data model · 4. Versioning strategy · 5. Selection and randomization ·
6. Scoring configuration · 7. Governance and permissions · 8. API design · 9. Database migration strategy ·
10. Screens · 11. Security considerations · 12. Test and performance strategy · 13. Acceptance criteria ·
14. Decisions needed · 15. Estimate · 16. Risks · 17. Follow-ups from 2.1

## 1. Product requirements

An **assessment** is an institution-scoped definition of what a candidate will answer and how it is marked. It is built
from **approved question versions only**, reviewed and approved independently, and **published** as an immutable
version that later increments schedule (2.4), deliver (2.5) and score (2.6).

| # | Requirement (from the authorization) | How 2.2 meets it |
|---|---|---|
| 1 | Creation and configuration | Assessment with code, title, institution, purpose, instructions, duration, navigation (free or sequential, back navigation on or off), section timing (optional), attempt policy (one attempt; resumable), calculator allowed (flag only; tool in 2.5), language |
| 2 | Purpose and reusable templates | Purposes ADMISSION, SEMESTER, INTERNAL, APTITUDE, PLACEMENT, PRACTICE, each with a **preset** (blueprint doc 05). Institution **templates** store reusable configuration, section skeletons and blueprint targets (never questions). Creating from a template copies it; later template changes never alter existing assessments |
| 3 | Approved question-bank selection | Fixed questions picked from approved versions, and random rules drawing from a bank with filters. Only banks the author may read and that belong to the assessment's institution or one of its ancestors. ADMISSION assessments draw only from banks whose intended uses include admission (high-stakes banks) |
| 4 | Subject, topic and difficulty distribution | **Blueprint**: target counts (or marks) per subject, topic, subtopic, difficulty, learning outcome, question type or tag; live "planned vs. target" table; validation states whether each target is *guaranteed* by the rules or not |
| 5 | Total marks and duration | Computed total (fixed questions plus rule count × marks), shown against an optional declared total; duration 1–600 minutes; per-section optional limits within the total |
| 6 | Negative marking and scoring | Per-version scoring configuration (§6): marks source (question or rule override), negative marking (off, question default, fixed value, or fraction of marks), partial credit (question type rules), unanswered = 0, section and total floors, rounding. Pure, versioned, previewable |
| 7 | Random question selection | Random rules (count from a filtered pool), sampling without replacement across rules, feasibility checked by maximum matching; pools frozen at publication (§5) |
| 8 | Answer-option randomization | Per section: shuffle option order for choice questions, respecting each question's own `shuffle: false`; option labels re-lettered for display while responses are recorded against stable option ids |
| 9 | Blueprint and versioning | Every change after publication creates a new draft version. Published versions are immutable (service and database); comparison between versions |
| 10 | Review and approval | Independent review and approval with policies like 2.1 (§7). Modification after review invalidates reviews |
| 11 | Draft, review, approved, published states | DRAFT → IN_REVIEW → APPROVED → PUBLISHED; then SUPERSEDED (newer version published) or WITHDRAWN (no new sessions) |
| 12 | Preview and validation | Validation report (blocking errors, warnings), candidate-view preview of a generated sample paper (no answer information), paper inspector for staff, deterministic regeneration by seed |

Also included:
- Every published version references its question versions permanently (`QuestionReferences`, 2.1). The questions
  can no longer be deleted, which turns 2.1 demonstration item 8 into a working feature.
- Events `assessment.published` and `assessment.withdrawn` (outbox).

## 2. User workflows

1. **Create.** An Assessment Designer chooses an institution and a purpose (or a template).
   - The preset fills duration, navigation, scoring and governance defaults; the draft opens in the editor.
2. **Build.** The designer works through the editor:
   - adds sections;
   - adds fixed questions with the question picker (approved versions only, from authorized banks);
   - adds random rules (bank, filters, count, marks), seeing the live pool size;
   - sets blueprint targets and watches planned against target;
   - sets scoring and option shuffling.
3. **Validate and preview.** Validation runs on demand and at every state change.
   - Preview generates a sample paper with a visible seed. "New sample" draws again; entering a seed reproduces a
     paper exactly.
4. **Submit for review.** This needs validation without errors.
   - A reviewer (never the author) checks the content, distribution, scoring, the preview papers and a checklist, then
     records *reviewed* or *changes requested* (with a reason).
5. **Approve.** A holder of `assessment.publish` (never the author; under high-stakes policy also not a reviewer)
   approves.
   - Validation re-runs live, for example to confirm the questions are still approved.
6. **Publish.** A holder of `assessment.publish` publishes the approved version.
   - Pools are frozen, question references are recorded, the version becomes immutable and `assessment.published` is
     emitted.
   - Publication may be done by the approver.
7. **Change after publication.** "New version" copies the published version into a new draft. The published version
   stays in force for scheduling until the new one is published, and then becomes SUPERSEDED. Sessions already created
   (from 2.4) keep the version they were created with.
8. **Withdraw.** A holder of `assessment.publish` withdraws a published version, with a reason.
   - No new sessions can use it. Existing sessions, papers and results are untouched.
9. **Templates.** Institution administrators and designers keep reusable templates, versioned simply as copies:
   editing a template never touches assessments created from it.

## 3. Data model (new module `assessments`; new tables only; all RLS by tenant)

| Table | Purpose | Key columns and constraints |
|---|---|---|
| `assessment_template` | Reusable configuration | org_unit_id (NULL = organization), purpose, name, config jsonb (validated), sections jsonb (skeleton), blueprint jsonb, status ACTIVE/ARCHIVED |
| `assessment` | Identity of an assessment | org_unit_id, code (unique per tenant), title, purpose, status ACTIVE/ARCHIVED, current_version_id, published_version_id, created_from_template_id |
| `assessment_version` | Everything that is reviewed and published | version_no, state (DRAFT, IN_REVIEW, APPROVED, PUBLISHED, SUPERSEDED, WITHDRAWN), config jsonb (duration, navigation, attempt policy, instructions as content-v1), scoring jsonb (§6), total_marks, policy_snapshot, review_hash, author_id, approved_by, published_by, timestamps; CHECKs: approver ≠ author, publisher ≠ author, state/actor consistency; one open version per assessment |
| `assessment_section` | Sections of a version | version_id, ordinal, title, instructions (content-v1), time_limit_minutes (optional), shuffle_questions, shuffle_options, navigation override |
| `assessment_item` | Fixed question in a section | section_id, ordinal, question_version_id (FK, must be APPROVED when added; same tenant), marks, negative_marks (override or NULL) |
| `assessment_rule` | Random selection rule | section_id, ordinal, bank_id, filters jsonb (subject, topic, subtopic, difficulty, outcomes, tags, types, metadata), count, marks, negative_marks |
| `assessment_rule_pool` | Frozen pool per rule, written at publication | rule_id, question_version_id; append-only; written once |
| `assessment_blueprint_target` | Distribution targets | version_id, dimension (SUBJECT, TOPIC, SUBTOPIC, DIFFICULTY, OUTCOME, TYPE, TAG), value, target_count or target_marks, tolerance |
| `assessment_review` | Reviews bound to the content hash | as `question_review` in 2.1 (reviewer ≠ author, hash-bound, invalidation) |
| `assessment_transition` | State history | append-only |
| `assessment_paper` | A generated paper (preview now; attempts from 2.5) | version_id, purpose (PREVIEW, VALIDATION, ATTEMPT), attempt_ref (NULL until 2.5), seed (256-bit), items jsonb (ordered question version ids, section, marks, option permutation), generator_version, paper_hash; **immutable once written** (trigger); unique per attempt_ref |
| `assessment_version_reference` | Permanent references to published versions from later records (sessions in 2.4, attempts in 2.5) | as `question_version_reference`: only PUBLISHED, SUPERSEDED or WITHDRAWN versions; never deleted |

Relationships:
- `assessment` 1—n `assessment_version` 1—n `assessment_section` 1—n (`assessment_item` | `assessment_rule` 1—n
  `assessment_rule_pool`).
- `assessment_version` 1—n `assessment_paper`.
- Questions are referenced only by **question version id**, never by question id.

## 4. Versioning strategy

- **Immutability.** A version in PUBLISHED, SUPERSEDED or WITHDRAWN is immutable, together with its sections, items,
  rules, pools, blueprint targets and scoring. This uses the 2.1 allowlist guard pattern: every column except the
  forward state change and bookkeeping. Sections, items, rules and pools are draft-only (trigger), like answer keys and
  image links in 2.1.
- **Forward-only states.** PUBLISHED → SUPERSEDED (when a newer version is published) or WITHDRAWN. Nothing returns to
  an editable state.
- **New versions.** Editing a published assessment creates version n+1 as a deep copy. Pools are not copied: they are
  re-frozen at the new publication.
- **Live re-validation.** Questions are re-checked at approval and at publication:
  - every fixed question version must still be APPROVED;
  - a fixed question whose approved version was SUPERSEDED or RETIRED blocks publication, and the editor offers
    "replace with current approved version" (a content change, so reviews are invalidated);
  - random pools are taken from the current approved versions at the moment of publication.
- **What later records reference.** Sessions (2.4), attempts and papers (2.5) and results (2.6) reference
  `assessment_version_id` through `AssessmentReferences` (module API). Such a version can never be deleted; the
  references are permanent.
- **Sessions keep their version.** Existing sessions keep their version even after it is superseded or withdrawn. This
  is guaranteed here by immutability and permanent references, and by 2.4 storing the version id. "Withdraw" blocks
  only *new* sessions.
- **Retiring a question after publication** never changes a published version or a generated paper (decision Q-2.2-3
  covers new papers).

## 5. Selection and randomization strategy

**Pool.** For each random rule, the pool is the set of current approved question versions in the rule's bank that
match its filters, excluding questions already fixed anywhere in the assessment. At publication the pool is frozen into
`assessment_rule_pool`, so every paper of that version draws from exactly the same pool, however the bank changes
later.

**Feasibility and validation.**
- Each rule needs a pool of at least its count.
- Overlapping pools are checked with a bipartite maximum matching between draws and questions (Hopcroft–Karp). This
  proves a paper without duplicates always exists.
- Exposure warning (Q-2.2-5): pool size less than 3 × count (default; a blocking error for ADMISSION).
- Blueprint targets are classified as *guaranteed* (fixed items plus rules constrained to that value add up exactly
  within tolerance) or *not guaranteed* (it depends on the draw). For ADMISSION, every target must be guaranteed.

**Paper generation** (`PaperGenerator`, a pure function and the `SelectionStrategy` SPI of blueprint doc 05).
- Inputs: the published version (sections, items, rules, frozen pools, shuffle flags) and a 256-bit seed.
- RNG: ChaCha20-based, a deterministic stream from the seed. There is no new library; it is implemented on the JDK's
  `Cipher` "ChaCha20".
- Algorithm:
  1. Fixed items are placed in section order.
  2. Random rules draw without replacement in a fixed order (section, then rule ordinal); a question drawn by an
     earlier rule is excluded from later ones.
  3. If the generator meets a dead end because of overlapping pools, it falls back to the matching solution, so it
     never fails after validation passed.
  4. Questions are shuffled within sections when enabled.
  5. Options are shuffled per question when enabled and the question allows it (Fisher–Yates over option ids).
- Output: ordered entries (section, question version id, marks, negative marks, option permutation) plus
  `generator_version` and the SHA-256 `paper_hash` of the canonical paper.
- Determinism: the same version, seed and generator version always produce the same paper. The generator is versioned;
  a change produces a new `generator_version` and old papers stay valid as stored.

**Recording.**
- Every generated paper is stored, never recomputed for use: preview papers in 2.2, one paper per candidate attempt in
  2.5 (through the same service, with `attempt_ref`).
- The seed comes from a CSPRNG and is stored with the paper, so a paper is auditable and reproducible.
- Papers are immutable, and the paper hash will be sealed into the attempt in 2.5.

**Fairness check.** The generator is statistically tested (§12): uniform selection within pools (chi-square) and
uniform option permutations.

## 6. Scoring configuration (deterministic and auditable)

Scoring is stored as versioned JSON per assessment version and validated on save. Schema `ivorovi.scoring.v1`:
- **Marks per question:** the question version's marks (default) or the item or rule override.
- **Negative marking:**
  - OFF;
  - QUESTION (the question version's negative marks);
  - FIXED (a value per item or rule);
  - FRACTION (k × marks, with k ∈ {¼, ⅓, ½, 1});
  - always ≤ marks.
- **Partial credit:** as defined by the question type (2.1: multiple-choice partial mode, short-answer percentages,
  numeric partial band). It can be disabled per assessment, in which case partial counts as incorrect.
- **Unanswered:** 0 (fixed).
- **Floors:** optional floors at 0 per section and for the total.
- **Rounding:** totals to 2 decimals, half-up; no intermediate rounding.

Properties:
- **Pure function.** `score(paper, responses, keys, scoring) → score lines` uses the 2.1 `QuestionType.mark`. It is
  used in 2.2 for the **scoring preview**: reviewers enter sample responses for a preview paper and see the totals. The
  same function is used for real attempts in 2.6.
- **Auditable.** The scoring configuration is part of the reviewed hash and the published version. Each score line will
  record the inputs (question version, response, marks, negative marks, rule version). Results can be recomputed
  exactly from responses and the published version.
- **No floating point.** BigDecimal throughout, as in 2.1 numeric evaluation.

## 7. Governance and permissions

### 7.1 Policies (same model as questions, 2.1)

| Policy | Rule | Default for purposes |
|---|---|---|
| STANDARD | 1 review; reviewer ≠ author; approver and publisher ≠ author | INTERNAL, PRACTICE |
| HIGH_STAKES | Author, reviewer and approver are three different people; publisher ≠ author | ADMISSION (always), SEMESTER, PLACEMENT, APTITUDE (default; institutions may relax only to STANDARD for SEMESTER, PLACEMENT and APTITUDE, through four-eyes, and never for ADMISSION) |

Rules:
- Institution floors apply (the strictest wins), as in 2.1.
- Any modification after a review invalidates it.
- The service enforces every rule, and the database trigger enforces them again at approval and publication.
- Permissions are checked at the assessment's institution: the approver and the publisher need `assessment.publish`
  there or above, checked live.

### 7.2 Permissions (refining the `assessment.*` placeholders of blueprint doc 13)

| Permission | Meaning | ORG_ADMIN | INSTITUTION_ADMIN | EXAM_CONTROLLER | ASSESSMENT_DESIGNER | FACULTY |
|---|---|---|---|---|---|---|
| `assessment.read` | view assessments, templates, versions, previews (never answer keys) | ✓ | ✓ | ✓ | ✓ | ✓ |
| `assessment.author` | create and edit drafts and templates, preview, submit | ✓ | ✓ | — | ✓ | Q-2.2-6 |
| `assessment.review` | review others' versions | ✓ | ✓ | ✓ | ✓ | — |
| `assessment.publish` | approve, publish, withdraw — **privileged** (Q-2.2-1) | — | — | ✓ | — | — |

- All are UNIT-scoped and inherit down the hierarchy.
- Administrators hold every non-privileged permission, so the 1.3 ceiling keeps working.
- `assessment.publish` joins the P2-08 privileged set: assigning a role that has it goes through four-eyes approval.
  The `privileged_permissions()` function and `Permission.PRIVILEGED` are extended, which is a documented dependency
  on 2.1.

**Access to banks and questions.**
- The question picker and rule builder list only banks where the user holds `question.read` and that belong to the
  assessment's institution or an ancestor (organization-wide banks).
- Only APPROVED question versions are offered, and only content and metadata, never answer keys.
- Reviewers who also hold the 2.1 key permissions can open a question's key through the existing audited endpoint.
- Assessment APIs never return keys.

## 8. API design (staff API, `/api/v1`; nothing on the delivery API in 2.2)

| Area | Endpoints |
|---|---|
| Templates | `GET/POST /assessment-templates`, `GET/PUT /assessment-templates/{id}`, `POST /assessment-templates/{id}/archive` |
| Assessments | `GET /assessments` (filters: institution, purpose, state, text), `POST /assessments` (from purpose or template), `GET /assessments/{id}` (detail with current and published versions, actions, policy) |
| Versions | `GET /assessments/{id}/versions/{no}`, `PUT /assessments/{id}` (edit the open version: config, scoring, sections and their items and rules, blueprint, with optimistic version), `POST /assessments/{id}/versions` (new draft from published), `GET /assessments/{id}/compare?from=&to=` |
| Selection helpers | `GET /assessments/{id}/question-picker` (approved versions in authorized banks; same filters as 2.1 search), `POST /assessments/{id}/rules/preview-pool` (pool size and distribution for a rule) |
| Validation and preview | `POST /assessments/{id}/validate` (errors, warnings, blueprint table, totals, feasibility), `POST /assessments/{id}/preview-papers` (generate; optional seed) → stored PREVIEW paper; `GET /assessment-papers/{id}` (candidate view: content and option order, no keys) and `GET /assessment-papers/{id}/inspect` (staff: question ids, marks, seed); `POST /assessment-papers/{id}/scoring-preview` (sample responses → score lines; requires key access to the questions, audited) |
| Workflow | `POST /assessments/{id}/submit`, `/reviews`, `/request-changes`, `/approve`, `/publish`, `/withdraw` (reasons where required), `GET /assessment-review-queue?stage=review|approval|publication`, `GET /assessments/{id}/transitions`, `/history` |
| Module API (Java, not HTTP) | `AssessmentReferences.reference(versionId, referrerType, referrerId)`; `PaperService.generate(versionId, purpose, attemptRef)` for 2.5; `ScoringService.score(...)` for 2.6 |

All writes are idempotent (`Idempotency-Key`) and audited. Errors use the existing problem format and never echo
question content or keys.

## 9. Database migration strategy

- **New migration** `V2026111300xx__assessments.sql`:
  - new tables (§3) with RLS on every table (`TenantIsolationIT` checks this automatically);
  - triggers: immutability allowlist, draft-only children, review binding and invalidation, approval and publication
    policy, append-only transitions and pools, immutable papers, permanent references;
  - a constraint trigger checking that `assessment_item.question_version_id` and pool rows are APPROVED versions of the
    same tenant when written;
  - grants of `assessment.*` to system roles (§7.2) and `privileged_permissions()` extended with `assessment.publish`.
- **No data migration:** no assessments exist today. The existing 2.0 readiness sessions are not assessment sessions
  and are not touched.
- **Documented dependencies on completed increments (only these):**
  1. `Permission` enum: the `assessment.*` placeholders become AVAILABLE with the refined names, and `PRIVILEGED`
     gains `assessment.publish`.
  2. `privileged_permissions()` is replaced (CREATE OR REPLACE) in the new migration.
  3. The question module offers a read-only `ApprovedQuestions` API (search approved versions by bank and filters, and
     delivery projection by version id) for the picker and the paper view. No change to 2.1 behaviour.
  4. If decided, the KI-75 search optimization changes the 2.1 search implementation behind the same API (§17).
- **Applied and tested the same way as 2.1:** on an empty database (CI), on the populated development database, and
  forward-only (no down migrations; the same Flyway rules as before).

## 10. Screens (staff app, new "Assessments" section)

| # | Screen | Content |
|---|---|---|
| 1 | Assessments | List with institution, purpose, state badge, published version, last change; filters; "New assessment" |
| 2 | New assessment | Choose institution and purpose (preset summary) or a template |
| 3 | Assessment editor | Tabs: **Settings** (instructions, duration, navigation, attempt policy); **Sections and questions** (fixed items and rules per section, pool sizes, marks); **Blueprint** (targets, planned vs. target, guaranteed or not); **Scoring** (negative marking, partial credit, floors, computed totals); live validation panel |
| 4 | Question picker | Search approved versions in authorized banks (2.1 filters), candidate-view preview (KaTeX and images), add to section |
| 5 | Random rule builder | Bank, filters, count, marks; live pool size and distribution; exposure warning |
| 6 | Preview | Candidate-view paper (sections, timing, shuffled options) with seed; "new sample"; staff inspector toggle; scoring preview for permitted staff |
| 7 | Assessment detail | Versions and states, comparison with the published version, reviews, transitions, audit history, actions (submit, review, approve, publish, withdraw, new version) |
| 8 | Review and publication queue | Waiting for my review, approval or publication (never my own) |
| 9 | Templates | Institution templates: list, create from an assessment or a purpose, archive |

Accessibility: the same standards as 2.1 (axe in E2E, keyboard operation, labelled controls). The manual checks
required by KI-77 also cover these screens before production use.

## 11. Security considerations

| Threat | Control |
|---|---|
| Publishing without independent approval | Service rules plus DB trigger (approver and publisher ≠ author; three people for HIGH_STAKES); `assessment.publish` privileged (P2-08 four-eyes assignment) |
| Changing a published assessment | Immutability allowlist trigger; children draft-only; pools append-only and write-once |
| Using unapproved or foreign questions | DB check on insert (APPROVED, same tenant); service checks bank access; re-validation at approval and publication |
| Seeing banks one is not authorized for | Picker and rule builder filter by `question.read` at the bank's unit and by the assessment's institution; API returns 404 for others |
| Answer-key exposure | Assessment APIs never include keys (leak tests as in 2.1: responses, search, logs, caches, errors, Valkey); scoring preview uses `AnswerKeyStore` access rules and is audited |
| Predictable papers | 256-bit CSPRNG seeds; preview papers marked PREVIEW and never usable as attempts; paper inspector only for staff with `assessment.read` at the institution. Candidate-facing papers arrive in 2.5 behind the delivery boundary |
| Paper tampering | Immutable paper rows plus `paper_hash` (sealed into attempts in 2.5) |
| Cross-tenant and cross-institution access | RLS on every table; service scope checks; isolation tests (API 404, RLS row counts) |
| Admission content handled by the wrong people | ADMISSION assessments only from high-stakes banks, with three-person governance |
| Denial of service through huge pools or papers | Limits: 20 sections, 500 items or draws per version, 50 rules, pool ≤ 50,000; validation p95 targets (§12) |

## 12. Test and performance strategy

**Unit tests**
- PaperGenerator:
  - determinism (same seed gives a byte-identical paper and hash);
  - no duplicates;
  - overlap fallback;
  - option shuffling respects `shuffle: false`;
  - chi-square uniformity over 100,000 draws for selection and permutations.
- Feasibility checker: matching edge cases and adversarial overlaps.
- Blueprint classification: guaranteed versus not guaranteed.
- Scoring: every negative-marking mode, partial credit on and off, floors, rounding, numeric and short-answer
  interplay; exact BigDecimal results.
- Configuration validation.

**Integration tests**
- **Lifecycle and governance:** standard and high-stakes paths; self-review, self-approval and self-publication
  refused (service and DB); review invalidation; floors.
- **Immutability:** SQL tamper attempts on the version, sections, items, rules, pools and papers.
- **Live re-validation:** a retired or superseded question blocks publication.
- **References:** published versions are referenced by question references; assessment references are permanent.
- **Isolation:** another institution or organization gets 404 and RLS returns zero rows; a candidate gets 401;
  API clients have no assessment scopes.
- **Permission matrix:** every role × endpoint.
- **Answer-key leak suite:** extended to assessment responses.
- **P2-08:** extension for `assessment.publish`.

**E2E (Playwright, including mobile and tablet projects)**
- Create from a purpose and from a template.
- Fixed and random selection; blueprint; validation errors and their fixes.
- Preview with the same seed giving the same paper.
- Review, approval and publication by three people.
- New version leaves the published version unchanged.
- Withdrawal.
- axe on every new screen.

**Performance (benchmark script, benchmark organization)**

| Measure | Target |
|---|---|
| Validation of an assessment with 10 rules over pools totalling 20,000 questions | p95 < 500 ms |
| Publication (pool freeze of 20,000 rows plus references) | < 5 s |
| Paper generation | p95 < 30 ms per paper; 2,000 papers in < 30 s, single instance, stored |
| Assessment list and detail | p95 < 200 ms |
| Question picker search | under the KI-75 targets |

## 13. Acceptance criteria

1. An Assessment Designer creates an institution-scoped assessment from a purpose preset and from a template.
2. Sections with fixed approved questions and random rules from authorized banks; unauthorized banks and unapproved
   questions are impossible (API and DB).
3. Blueprint targets with planned vs. target, and the guaranteed or not-guaranteed classification; ADMISSION requires
   guaranteed targets.
4. Total marks, duration, negative marking and partial-credit configuration validated, with a correct scoring preview.
5. Random selection and option shuffling are deterministic per seed, stored as immutable papers with a hash, and pass
   the uniformity tests.
6. Validation blocks infeasible pools and shows warnings for low exposure.
7. Independent review, approval and publication with service and DB enforcement; three people for HIGH_STAKES; reviews
   invalidated on change.
8. A published version is immutable. A new version leaves it in force until superseded. Withdrawal blocks only new use.
   Version references are permanent.
9. Published versions reference immutable question version ids, recorded as question references.
10. No answer key in any assessment response, log, cache or error (leak suite).
11. Tenant isolation and the permission matrix are verified (API and RLS); `assessment.publish` is assigned only
    through four-eyes.
12. Performance targets met (§12); CI green on all jobs; documentation (ADR-0011, runbook, record) complete.

## 14. Decisions needed

| # | Question | Options | Recommendation |
|---|---|---|---|
| Q-2.2-1 | Is `assessment.publish` privileged (P2-08 four-eyes assignment)? | (a) yes (b) no | **(a)**: publication releases examination content; it matches `question.approve` |
| Q-2.2-2 | Governance per purpose | (a) as §7.1 (ADMISSION always high-stakes; SEMESTER, PLACEMENT and APTITUDE high-stakes by default, relaxable to standard through four-eyes; INTERNAL and PRACTICE standard) (b) all high-stakes (c) all standard with floors | **(a)** |
| Q-2.2-3 | A question retired *after* an assessment version is published: may new papers of that version still draw it? | (a) yes, the version is frozen; the detail page shows a warning and offers "new version" (b) exclude it automatically from new papers (the version would then behave differently from what was approved) | **(a)**: immutability of what was approved; errors in a question are handled by a new version, and in delivered papers by item cancellation in 2.6 |
| Q-2.2-4 | Seed source | (a) CSPRNG seed stored per paper (b) HMAC-derived from a secret and the attempt id | **(a)**: simpler, auditable, no extra secret to manage |
| Q-2.2-5 | Low exposure (pool < 3 × draw count) | (a) warning; blocking for ADMISSION (b) warning only (c) blocking always | **(a)** |
| Q-2.2-6 | May FACULTY author assessments? | (a) yes, all purposes (b) only INTERNAL and PRACTICE (c) no | **(b)**: purpose-limited authoring, enforced in the service; FACULTY never authors ADMISSION or SEMESTER papers |

## 15. Revised engineering estimate

| Work | Days |
|---|---|
| Data model, migration, RLS, triggers (immutability, governance, draft-only, papers, references) | 5–6 |
| Templates and purpose presets, configuration validation | 2–3 |
| Fixed items, random rules, pool queries, feasibility (matching), blueprint classification | 4–5 |
| PaperGenerator (deterministic RNG, shuffles, fallback), paper storage, preview and inspector | 3–4 |
| Scoring configuration and scoring preview | 2–3 |
| Workflow, reviews, approval, publication, withdrawal, queues, P2-08 extension | 3–4 |
| API, module APIs (`AssessmentReferences`, `PaperService`, `ScoringService`), question-module read API | 2–3 |
| Frontend: 9 screens (editor, picker, rule builder and preview are the large ones) | 10–12 |
| Tests: unit and statistical, integration matrix, leak suite, isolation | 5–6 |
| E2E, accessibility, benchmark, docs (ADR-0011, runbook, record), CI, review fixes | 4–5 |
| **Total 2.2** | **40–51 engineering days** |
| Optional, recommended: KI-75 search optimization (§17) | +3–5 |

This is more than the roadmap's two weeks. The increase comes from the database-enforced governance and immutability,
the frozen pools with feasibility proofs, stored and hashed papers with statistical tests, and the scoring preview. All
of these were required by the authorization and follow the 2.1 standard. It is a planning estimate, not a commitment.

## 16. Risks

| Risk | Mitigation |
|---|---|
| Large pools make publication and validation slow | Set-based SQL for the pool freeze; benchmark at 20,000; limits |
| Matching complexity for many overlapping rules | Hopcroft–Karp on ≤ 500 draws × ≤ 50,000 candidates, pre-filtered to the union of pools; benchmark |
| Statistical tests flaky | Fixed seeds for the deterministic suite; chi-square at α = 0.001 over a fixed seed set, so the result is reproducible and never retried |
| Picker searches hit KI-75 | KI-75 optimization scheduled first (§17) |
| Scope creep into scheduling and delivery | Papers carry no attempt binding until 2.5; no delivery endpoints in 2.2 |

## 17. Follow-ups from Increment 2.1 (visible in tracker and known-issues register)

| Item | Plan |
|---|---|
| **KI-75** search performance (p95 < 250 ms at 60,000, < 300 ms at 100,000) | Recommended **first work package of 2.2**, because the question picker depends on it. A tenant- and bank-scoped search function using the full-text and trigram indexes (same pattern and isolation tests as `question_near_duplicate`), plus a covering index for list ordering. Benchmarked at 60,000 and 100,000. RLS stays enabled on every table, and no index use bypasses the tenant predicate |
| **KI-77** manual accessibility | Needs people and assistive technology (NVDA, JAWS, VoiceOver, TalkBack). I will prepare a test script and checklist (mathematics, navigation, image descriptions, keyboard, form errors), but the sessions must be done by a tester; results are recorded in the register. Required before production examination use |
| **KI-78** real-device Safari | Needs physical iPhone, iPad and Mac. Checklist prepared with KI-77; to be run by someone with devices. Required before production examination use |
| **KI-79** flaky candidate-recovery test | Root cause documented (the recovery guard also ends the session the reset just created). The proposed product fix changes 2.0 code, so it needs your approval: end only sessions started before the reset, and make the test deterministic. Retries are not counted as evidence |
