# Increment 2.1 — Question bank (revised plan, for final review)

Status: **revised 2026-11-08 after the product owner's "plan approval with modifications"; awaiting final review.
No implementation has started.** Authorized scope: Increment 2.1 only. Not in scope: AI question generation or
evaluation, audio and video questions, matching, ordering, fill-in-the-blank and other advanced interactive types,
assessment assembly (2.2). Phase 1 and 2.0 functionality is not changed except where §11 lists an approved dependency.

**One item needs your explicit approval before implementation:** the math renderer KaTeX (MIT licence) is a new
frontend library (§6.3, ADR-0010 proposal). Everything else uses components already in the platform.

## Contents

1. Goal and scope · 2. Architecture rules and how they are met · 3. Data model · 4. Question types ·
5. Version and approval workflow · 6. Content, images and mathematics · 7. Answer-key security · 8. Taxonomy and
metadata · 9. API design · 10. Permission matrix · 11. Migration strategy · 12. Screens · 13. Test plan ·
14. Acceptance criteria · 15. Estimate · 16. Risks

## 1. Goal and scope

An institution-scoped bank of versioned questions with a configurable, four-eyes-or-stricter review and approval
workflow, ready to be referenced by immutable version id from assessments (2.2 on). Scope:

1. Question creation, editing and versioning.
2. Question types: single-answer MCQ, multiple-answer MCQ, true/false, short answer, numeric response.
3. Mathematical notation and images in statements and options, with alternative text and malware scanning.
4. Taxonomy: subject, topic, subtopic, difficulty, learning outcome, tags, marks, negative marking, institution metadata.
5. Review and approval workflow: Draft → In review → Reviewed → Approved → Retired, Superseded for older versions.
6. Configurable approval policies (standard, high-stakes, stricter institution rules; never self-approval).
7. Institution-scoped banks with unit-level access control.
8. Search, filters and bulk import (CSV/XLSX) with validation, preview and duplicate detection.
9. Encrypted, separately stored answer keys with access control and audit of every view and change.
10. Full audit history, permission enforcement and lifecycle transition records.

## 2. Architecture rules and how they are met

| Rule | Mechanism |
|---|---|
| Approved versions immutable | Database trigger forbids content changes on APPROVED, SUPERSEDED and RETIRED versions and on their answer keys and asset links, and forbids deleting any non-draft version or its key; the service has no update path for them |
| Modification of an approved question = new draft version + full review and approval | "Edit" creates version n+1 as DRAFT (copy of content, key and asset links); the approved version stays current until n+1 is approved |
| Any modification after review invalidates the review | Reviews record the *review hash* of exactly what was reviewed (content, options, key, marking, assets); any edit returns the version to DRAFT and invalidates its reviews; approval is accepted only with valid reviews whose hash equals the current hash — checked by the service **and** by a database trigger |
| Never self-approval; policy-dependent separation | Service rule + database constraints and an approval trigger (§5.3) |
| Published assessments reference immutable version ids | Version ids are permanent; 2.2 references `question_version.id`; retirement and supersession change only the state column, never content |
| Answer keys never exposed | Separate encrypted table, access checked in the API layer and again in the data-access layer, every view and change audited, excluded from candidate APIs, exports, search indexes, logs, caches and error responses (§7) |
| Tenant and organizational isolation | RLS on every table; banks belong to an organization unit; access down the hierarchy only to holders of the relevant permission; assets belong to a bank |
| Reusable across admissions, academics, placement | Questions are not tied to a purpose; banks declare intended uses; taxonomy subjects may link to academic subjects (1.4) but need not |
| Extensible types and metadata | Question-type plug-ins (`QuestionType` SPI) with type-specific JSON validated per type and schema version; institution attribute definitions — no schema change for a new type or field |
| Every important action audited, with actor, time, reason, transition | `question_transition` table (one row per state change with actor, timestamp, from/to, reason) plus audit events in the hash-chained audit log |

## 3. Data model (one migration, new tables only)

```
-- banks and policy
question_bank (id, tenant_id, org_unit_id, code, name, description,
               intended_uses text[]  {ADMISSION_EXAM, ADMISSION_SCREENING, ACADEMIC, PLACEMENT, PRACTICE},
               approval_policy STANDARD|HIGH_STAKES, required_reviews 1..3, approver_must_differ_from_reviewers bool,
               default_marks, default_negative_marks, status ACTIVE|ARCHIVED, created_*/updated_*, version)
  CHECK: 'ADMISSION_EXAM' = ANY(intended_uses) ⇒ approval_policy = 'HIGH_STAKES'
  CHECK: approval_policy = 'HIGH_STAKES' ⇒ approver_must_differ_from_reviewers
institution_question_policy (tenant_id, org_unit_id, minimum_policy, minimum_reviews, approver_distinct)  -- floor for banks below the unit

-- taxonomy (institution-configurable)
question_subject (id, tenant_id, org_unit_id, code, name, academic_subject_id → subject (optional), status)
question_topic   (id, tenant_id, subject_id, parent_id, level 1=topic|2=subtopic, code, name, status)
learning_outcome (id, tenant_id, subject_id, code, description, bloom_level, status)
difficulty_level (id, tenant_id, org_unit_id, code, label, rank, status)           -- defaults: 5 levels
question_tag     (id, tenant_id, org_unit_id, name)                                  -- managed tags; free tags allowed per bank setting
question_attribute_definition (id, tenant_id, org_unit_id, key, label, type TEXT|NUMBER|BOOLEAN|CHOICE|DATE,
                               allowed_values text[], required_for_approval bool, status)

-- questions and versions
question (id, tenant_id, bank_id, current_version_id, approved_version_id NULL, status ACTIVE|RETIRED, created_*, version)
question_version (id, tenant_id, question_id, version_no,
     state DRAFT|IN_REVIEW|REVIEWED|APPROVED|SUPERSEDED|RETIRED,
     type_code, type_schema_version,
     content jsonb          -- statement in the content format (§6.1), no answer information
     body jsonb             -- type-specific, non-secret parts (e.g. options with ids and content; numeric unit/precision shown to candidates)
     marks numeric(7,2) > 0, negative_marks numeric(7,2) ≥ 0, marking jsonb (non-secret marking parameters, e.g. scoring mode),
     subject_id, topic_id, subtopic_id, difficulty_id, outcome_ids uuid[], tags text[], attributes jsonb, language,
     review_hash char(64)   -- SHA-256 over canonical content+body+key+marking+asset ids (computed by the service)
     dup_hash char(64), search_text tsvector (generated from text runs of content and options only),
     policy_snapshot jsonb  -- bank policy captured at submission
     author_id, created_at, submitted_at, approved_by, approved_at, retired_by, retired_at, source MANUAL|IMPORT, import_id, version)
  UNIQUE (question_id, version_no); partial UNIQUE: one open (DRAFT/IN_REVIEW/REVIEWED) version per question
  CHECK approved_by IS NULL OR approved_by <> author_id
question_answer_key (version_id PK, tenant_id, key_enc text, explanation_enc text, key_hash char(64), updated_at)
  -- AES-GCM via FieldCipher, AAD = column + tenant; separate table, separate access path (§7)
question_review (id, tenant_id, version_id, reviewer_id, decision REVIEWED|CHANGES_REQUESTED, reviewed_hash, checklist jsonb,
                 comment, created_at, invalidated_at, invalidated_reason)
  CHECK reviewer_id <> (author of the version)   -- enforced by trigger (author lives on the version row)
question_transition (id, tenant_id, version_id, from_state, to_state, actor_id, reason, at)       -- append-only (no UPDATE/DELETE grant)

-- assets
question_asset (id, tenant_id, bank_id, sha256, media_type image/png|image/jpeg, width, height, bytes,
                scan_status PENDING|CLEAN|INFECTED|ERROR|NOT_SCANNED_DEV, original_key, served_key, alt_text_default,
                uploaded_by, created_at)
question_version_asset (version_id, asset_id, usage STATEMENT|OPTION, option_id, alt_text, decorative bool)   -- frozen with the version

-- import
question_import (id, tenant_id, bank_id, file metadata, object key, scan_status, status UPLOADED|PREVIEWED|COMMITTED|FAILED|EXPIRED,
                 counts, created_*, committed_*)
question_import_row (id, tenant_id, import_id, row_no, outcome VALID|INVALID|DUPLICATE_IN_FILE|DUPLICATE_IN_BANK|NEAR_DUPLICATE,
                     errors jsonb, payload jsonb (no key), key_enc, matched_question_id, similarity)
```

* Every table: `tenant_id`, RLS `tenant_id = current_tenant_id()`, composite foreign keys `(tenant_id, …)`.
* `pg_trgm` (trusted extension) for near-duplicate detection on the normalized statement; `dup_hash` for exact duplicates.
* Retention: versions, keys, reviews, transitions and assets referenced by any non-draft version are never deleted;
  unreferenced draft assets are purged after 30 days.

## 4. Question types

Common to all types: statement (`content`), marks, negative marks (deducted only when an answer is given and wrong),
taxonomy, attributes. Each type is a `QuestionType` plug-in: `validateBody`, `validateKey`, `validateMarking`,
`deliveryProjection` (candidate view), `canonicalForHash`, `normalizedForDuplicates`, `importColumns`. Adding a type =
new plug-in + `type_code`; no table changes. `type_schema_version` allows evolving a type's JSON.

| Type | Body (non-secret) | Answer key (encrypted) | Marking rules |
|---|---|---|---|
| `SINGLE_CHOICE` | 2–10 options `{id, content}`; shuffle allowed flag | exactly one correct option id | full marks if correct; negative marks if wrong |
| `MULTIPLE_CHOICE` | 2–10 options; min/max selections shown to candidate (optional) | ≥ 1 correct option ids | `ALL_OR_NOTHING` (default) or `PARTIAL`: marks × (correct selected − wrong selected) / correct total, floored at 0; negative marks only for all-or-nothing wrong answers or when configured `negative_on_any_wrong` |
| `TRUE_FALSE` | statement only (labels configurable: True/False) | `true` or `false` | full marks / negative marks |
| `SHORT_ANSWER` | expected input length, case sensitivity shown? (no) | 1–20 accepted answers; match mode `EXACT`, `CASE_INSENSITIVE`, `NORMALIZED` (case, whitespace, punctuation, Unicode NFKC) ; optional per-answer partial percentage | full or partial marks on first matching accepted answer; else negative marks if answered |
| `NUMERIC` | unit label (optional), decimal places shown to the candidate (`precision`, 0–10), input range hint (optional) | correct value (decimal string, exact `BigDecimal`), tolerance `ABSOLUTE` or `RELATIVE_PERCENT` with value ≥ 0, rounding rule for the response `ROUND_HALF_UP` to `precision` or `REJECT_EXCESS_PRECISION`, optional partial band `{tolerance, percent}`, optional additional accepted values (e.g. equivalent forms) | response parsed as decimal (locale-safe: digits, one decimal point, optional sign, optional exponent off by default), rounded per rule, compared with BigDecimal arithmetic: within tolerance → full marks; within partial band → percent of marks; otherwise negative marks if answered |

Scoring itself runs in 2.6; 2.1 stores and validates the rules and includes a reference *marking preview* (staff
only) that shows how sample responses would be marked, so authors and reviewers can check numeric tolerances.

## 5. Version and approval workflow

### 5.1 States and transitions

| From → to | Who | Conditions | Reason |
|---|---|---|---|
| (new) → DRAFT | author (`question.author`) | — | — |
| DRAFT → IN_REVIEW (submit) | the version's author | complete, valid per type, all assets CLEAN, alt text present for non-decorative images, required attributes present; policy snapshot taken | optional |
| IN_REVIEW → IN_REVIEW (review recorded) | reviewer (`question.review`), ≠ author, not already reviewed this hash | — | comment |
| IN_REVIEW → REVIEWED | automatic when valid reviews ≥ `required_reviews` | — | — |
| IN_REVIEW/REVIEWED → DRAFT (changes requested) | reviewer or approver | — | **required** |
| IN_REVIEW/REVIEWED → DRAFT (edit) | author | reviews invalidated | automatic ("modified after review") |
| REVIEWED → APPROVED | approver (`question.approve`) | policy check §5.3; review hashes = current hash | optional |
| APPROVED(n) → SUPERSEDED | system, when n+1 is approved | — | "superseded by version n+1" |
| APPROVED/SUPERSEDED → RETIRED | approver | — | **required** |
| APPROVED → new DRAFT (n+1) | author | copies content, key, assets | optional |
| DRAFT → deleted | author | only versions never submitted… and never referenced | — |

Every transition writes a `question_transition` row (actor, timestamp, from, to, reason) and an audit event.

### 5.2 Approval policies

| Policy | Rule | Applies |
|---|---|---|
| `STANDARD` | author ≠ every reviewer; author ≠ approver; `required_reviews` ≥ 1 | default for academic, placement, practice banks |
| `HIGH_STAKES` | author, reviewer(s) and approver are all different people; `required_reviews` ≥ 1 | mandatory for banks marked `ADMISSION_EXAM`; selectable for any bank |
| Stricter institution settings | per unit: minimum policy, minimum number of distinct reviewers (1–3), approver distinct from reviewers | applies to all banks below the unit; a bank can be stricter, never weaker |

Self-approval is impossible under every policy. Weakening a bank's policy (or an institution floor) requires a
four-eyes approval through the 2.0 approvals framework and is refused while versions of that bank are in review;
strengthening applies at once. Versions keep the policy snapshot taken at submission.

### 5.3 Database-level integrity

* `CHECK (approved_by <> author_id)` on `question_version`.
* Trigger on `question_review` insert: reviewer ≠ the version's author; version state is IN_REVIEW.
* Trigger on transition to APPROVED: approver ≠ author; count of distinct reviewers with `decision = REVIEWED`,
  `invalidated_at IS NULL` and `reviewed_hash = version.review_hash` ≥ snapshot `required_reviews`; when the snapshot
  requires it, approver ∉ those reviewers. A direct SQL `UPDATE … SET state = 'APPROVED'` that violates any rule fails.
* Trigger on content/key/asset-link change: allowed only in DRAFT; changing `review_hash` while reviews exist marks
  them invalidated (defence in depth; the service already does so).
* Immutability trigger (§2); `question_transition` has no UPDATE/DELETE grant for the application role.

## 6. Content, images and mathematics

### 6.1 Content format (no HTML anywhere)

Statements and option contents are stored as a small structured document, **never as HTML**:

```json
{ "format": "ivorovi.content.v1",
  "blocks": [
    { "type": "paragraph", "runs": [ {"text": "Evaluate "}, {"math": "\\int_0^1 x^2\\,dx"}, {"text": "."} ] },
    { "type": "math", "tex": "E = mc^2" },
    { "type": "image", "asset": "0192…", "alt": "Circuit with two resistors in series", "decorative": false } ] }
```

* Block types: `paragraph` (runs of `text` and inline `math`, optional `bold`/`italic`/`sub`/`sup` marks), `math`
  (display), `image`, `list` (ordered/unordered of paragraphs). Unknown types or properties are rejected.
* Limits: 20,000 characters per statement, 2,000 per option, 1,000 per formula, 10 images per version.
* Rendering builds DOM nodes from this structure (React elements; text is always text nodes). There is no path from
  question content to `innerHTML`, `dangerouslySetInnerHTML`, `eval` or URLs other than our own asset endpoint.
* Editor: a structured editor with a plain-text source mode (`$…$` inline and `$$…$$` display math, image picker);
  the source is parsed into the format on save — on the server as well, which is authoritative.

### 6.2 Images

| Concern | Design |
|---|---|
| Accepted input | PNG and JPEG only (SVG, GIF, WebP, HEIC rejected); ≤ 5 MB; ≤ 4,096 × 4,096 px; header dimensions checked **before** decoding (decompression-bomb guard) |
| Sanitising | decoded and **re-encoded** with the JDK's ImageIO (no new library): strips metadata (EXIF/GPS), comments and any appended or polyglot data; the served file is the re-encoded one |
| Malware scanning | original stored in quarantine (`t/{tenant}/quarantine/qa/…`); scanned with the existing `MalwareScanner` (ADR-0006, fail-closed outside development; RB-1 stays a production blocker); only CLEAN assets are re-encoded, promoted and servable; a version with a non-CLEAN asset cannot be submitted |
| Storage and access | object storage under `t/{tenant}/question-assets/{bank}/{sha256}`; content-addressed and immutable; never public; served by `GET /api/v1/question-assets/{id}` after checking `question.read` on the asset's bank (institution-level control), with `Content-Type` fixed, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`, `Content-Security-Policy: sandbox; default-src 'none'`, `Cache-Control: private, max-age=300`; candidate delivery (2.5) will use attempt-bound short-lived URLs |
| Accessibility | alt text (1–250 characters) required per use unless explicitly marked decorative; checked at submission and listed on the review checklist; previews show alt text |
| Preview | thumbnails in the editor and asset library; full preview in the question preview, as the candidate will see it |
| Reuse | assets belong to a bank; a version can only reference assets of its own bank (database foreign key on `(tenant_id, bank_id)` through the version's question) |

### 6.3 Mathematics — technology decision (ADR-0010, proposed)

**Recommendation: KaTeX 0.16 (MIT), MathML output only, `trust: false`, strict mode, bounded expansion — new
dependency, needs your approval.**

| Option | Assessment |
|---|---|
| **KaTeX, `output: "mathml"`** (recommended) | Mature, fast, small (~75 kB gzip, loaded lazily only on screens with math); MathML output contains no inline styles or HTML, so it works under the exam app's strict CSP (`style-src 'self'`) without loosening it; `trust: false` disables `\href`, `\url`, `\includegraphics`, `\html…`; `maxSize`, `maxExpand` bound resource use; rendered into our own element from a parsed tree, never from question HTML; all current browsers render MathML Core (Chrome 109+, Firefox, Safari) |
| KaTeX HTML output | Needs inline `style` attributes and its stylesheet/fonts → would require `style-src 'unsafe-inline'` in the exam app; rejected |
| MathJax 3 | Larger, configurable but heavier attack surface (extensions, menus); SVG/CHTML outputs use inline styles; no advantage here |
| Server-side rendering (Java) | No mature, maintained Java TeX-to-MathML library; would add a JVM dependency of uncertain quality |
| Authoring MathML directly | Safe but impractical for authors |

Server-side defences independent of the renderer: formula length limits, a deny-list of commands
(`\href`, `\url`, `\includegraphics`, `\htmlClass`, `\htmlId`, `\htmlStyle`, `\htmlData`, `\def`, `\gdef`, `\newcommand`,
`\renewcommand`), balanced-brace check; the client renders with the same options in the staff app (editor preview)
and the exam app (2.5). Rendering failures show the source text, never raw HTML.

## 7. Answer-key security

| Requirement | Design |
|---|---|
| Separate, encrypted storage | `question_answer_key` table; key and explanation encrypted with `FieldCipher` (AES-256-GCM, AAD = column + tenant); `key_hash` for integrity and review hashing |
| Access at the API layer | Key-bearing responses only from dedicated endpoints/fields: `GET /questions/{id}/versions/{no}/answer-key`, and the editor payload for the version's author; method-level permission checks |
| Access at the data-access layer | `AnswerKeyStore` is the only class that reads the table; it re-checks the caller's right for that version (rule below) and the tenant, independently of the controller; RLS limits rows to the tenant; an ArchUnit test forbids any other class from selecting the table |
| Who may see a key | DRAFT: its author. IN_REVIEW/REVIEWED: author, holders of `question.review` or `question.approve` at the bank. APPROVED/SUPERSEDED/RETIRED: holders of `question.key.read` or `question.approve` at the bank. `question.bank.manage` **never** grants key access |
| Audit | every key view (`question.answer_key.viewed`: version, actor, purpose) and every key change (`question.answer_key.changed`, old/new key hash only, never the key) |
| Candidate APIs | none in 2.1; the delivery process returns 404 for `/api/v1/question*`; `QuestionDeliveryView` (for 2.5) is built by the type plug-in from `body` only and is tested to contain no key, explanation or marking-sensitive data |
| Bulk exports | no question export in 2.1; the import template contains example rows only; any future export of keys needs `question.key.read`, four-eyes approval and an audit event |
| Search indexes | `search_text` is generated from statement and option text only; keys and explanations are never indexed; no external search engine |
| Logs | key values never logged: DTOs holding keys override `toString`; request/response bodies are not logged; a test captures application output during key operations and asserts the plaintext never appears |
| Caches | key-bearing responses carry `Cache-Control: no-store`; keys are never put in Valkey or any application cache; the principal cache holds no question data |
| Error responses | validation errors reference field paths and codes only (never echo submitted values); the global handler never includes exception messages; tests submit invalid keys and assert the error body does not contain them |
| Import | key columns are encrypted per row on upload; the preview shows keys only to the importing author; raw files stay in quarantine and expire (30 days) |

## 8. Taxonomy and metadata

* **Subject → topic → subtopic** per institution (unit); subjects can link to an academic subject from 1.4 but
  admission and placement subjects (e.g. "Quantitative aptitude") need no academic link.
* **Difficulty**: institution-configurable levels (code, label, rank); default five levels created on first use.
* **Learning outcomes** per subject with optional Bloom level.
* **Tags**: managed tag list per institution, plus free tags if the bank allows them.
* **Marks and negative marking**: per version, with bank defaults; validation per type.
* **Institution-specific metadata**: attribute definitions (text, number, boolean, choice, date; required-for-approval
  flag) — validated server-side, searchable by equality filters.
* Taxonomy entries are never deleted when in use; they can be deactivated (hidden from new questions).

## 9. API design (staff API; all under `/api/v1`)

| Area | Endpoints (permission) |
|---|---|
| Banks | `GET/POST /question-banks`, `GET/PUT /question-banks/{id}` (read / `question.bank.manage`); `PUT …/{id}/policy` (bank.manage; weakening → approval request) |
| Institution policy | `GET/PUT /question-policies/{unitId}` (bank.manage; weakening → approval request) |
| Questions | `GET /question-banks/{id}/questions` search + filters (read); `POST /question-banks/{id}/questions` (author); `GET /questions/{id}`, `GET /questions/{id}/versions/{no}` (read; no keys); `PUT /questions/{id}/draft` (author of the draft); `POST /questions/{id}/versions` (author); `DELETE /questions/{id}/draft` (author, never-submitted drafts only) |
| Answer keys | `GET /questions/{id}/versions/{no}/answer-key` (rule §7, audited); key is written with the draft (author) |
| Workflow | `POST /questions/{id}/submit` (author) · `/reviews` (review) · `/request-changes` (review/approve, reason) · `/approve` (approve) · `/retire` (approve, reason); `GET /questions/{id}/transitions`, `/history` (read) |
| Queues | `GET /question-review-queue?stage=review|approval` (review / approve; never own questions; respects policy) |
| Preview | `POST /questions/{id}/versions/{no}/marking-preview` (author/review/approve; sample responses → marks) |
| Assets | `POST /question-banks/{id}/assets` (multipart, author); `GET /question-assets/{id}` (read on bank, CLEAN only); `GET /question-banks/{id}/assets` (read) |
| Taxonomy | `/question-taxonomy/subjects`, `/topics`, `/learning-outcomes`, `/difficulty-levels`, `/tags`, `/attributes` (read / bank.manage) |
| Import | `GET /question-imports/template?type=`; `POST /question-banks/{id}/imports`; `GET /question-imports/{id}`, `…/rows`; `POST …/commit` (author; idempotent) |

All mutating endpoints take the optimistic `version` and support `Idempotency-Key` like the rest of the API.

## 10. Permission matrix (P2-07, revised by P2-08 on 2026-11-08)

| Permission | Meaning | ORG_ADMIN | INSTITUTION_ADMIN | EXAM_CONTROLLER | ASSESSMENT_DESIGNER | FACULTY |
|---|---|---|---|---|---|---|
| `question.read` | view banks, questions, assets (no keys) | ✓ | ✓ | ✓ | ✓ | ✓ |
| `question.author` | create/edit own drafts, upload assets, import, submit; key of own drafts | ✓ | ✓ | — | ✓ | ✓ |
| `question.review` | review others' versions; keys of versions in review | ✓ | ✓ | ✓ | ✓ | — |
| `question.approve` | approve, retire, request changes; keys of versions in review and approved — **privileged** | — | — | ✓ | — | — |
| `question.key.read` | view keys of approved, superseded, retired versions — **privileged** | — | — | ✓ | — | — |
| `question.bank.manage` | banks, policies, taxonomy, attribute definitions, image quotas (**no key access**) | ✓ | ✓ | ✓ | — | — |

All UNIT-scoped (inherit down the hierarchy). The placeholders `assessment.read/manage` stay PLANNED for 2.2.
ADMISSION_OFFICER and others get nothing by default.

Why administrators hold author and review (P2-08): the Phase 1.3 privilege ceiling lets an administrator assign only
roles whose permissions they hold. With the original matrix, administrators could no longer assign FACULTY or
ASSESSMENT_DESIGNER (regression KI-74). Administrators therefore hold read, author, review and bank management. They
never hold `question.approve` or `question.key.read` by default. Through review they can see the keys of versions
**in review**, never of approved versions. `question.bank.manage` on its own still grants no key access.

### 10.1 Privileged role assignment (P2-08)

`question.approve` and `question.key.read` are **privileged permissions**. Any role containing one of them (for
example EXAM_CONTROLLER, or a custom role) is a **privileged role**.

| Rule | Enforcement |
|---|---|
| Assigning a privileged role never takes effect directly; it opens a four-eyes request of type `role.privileged_assignment` in the 2.0 approval framework | Service (`AssignmentService`) and database (trigger on `role_assignment`) |
| The requester must hold `role.assign` and every **non-privileged** permission of the role at the scope. This is the unchanged 1.3 ceiling, except that the privileged permissions are not required of the requester | Service; database re-check at activation |
| The requester can never decide their own request; the target user can never approve their own elevation | Service; `approval_four_eyes` constraint; trigger |
| The approver must currently hold **every** privileged permission of the role at the assignment's scope (that unit or an ancestor; the organization for organization-wide assignments) | Service hook (live); SQL check with row locks in the activation transaction; trigger |
| The assignment exists only after approval: no inactive or pending row in `role_assignment` | Trigger: an application-role insert of a privileged role needs an APPROVED, unused request whose payload matches user, role and scope exactly |
| Live checks at approval **and** at activation (the same transaction): approver, requester, target account active, role active and unchanged | Service (`unchanged` hook, permission hash) and trigger |
| If the requester or approver lost the required permissions, or the role or target changed, the approval is invalidated: the request becomes CANCELLED with reason "invalidated" and nothing is assigned | Framework (handler veto) |
| A concurrent revocation of the approver's grants cannot slip past: the activation check locks the approver's assignment rows (`FOR SHARE`) | Database row locks |
| Cross-institution: an Institution Admin can only request within their scope; an approver from another institution does not hold the permissions at the scope and is refused | Service and trigger |
| Changing a role so that it gains a privileged permission while it has active assignments is refused for the application role (create a new role and assign it through approval) | Service ceiling (1.3) and trigger on `role` |
| Revoking a privileged role needs `role.assign` and the non-privileged ceiling only, so an administrator can always remove an Exam Controller. Revocation never grants anything | Service |
| Every request, decision, refusal, invalidation, activation and bootstrap is audited (`approval.*`, `role.privileged_assignment_*`, `role.assigned`, `role.privileged_bootstrap`) | AuditLog |
| One pending privileged-assignment request per target user at a time | `approval_open_uq` index |

**Existing holders.** Assignments of EXAM_CONTROLLER that existed before this migration gain the question permissions
through the system role and are kept as they are. They are eligible approvers.

**Bootstrap of the first privileged role.** An organization (or an institution) with no current holder of the
privileged permissions at or above the scope cannot obtain an approver. The bootstrap is an operator procedure, not
an administrator self-service exception:

1. The organization requests it through the support process. The request names the person, the role and the scope,
   and identifies the requesting administrator.
2. A platform operator with database operations access runs the one-shot bootstrap command
   (`--ivorovi.ops.bootstrap-privileged-role=…`, see `docs/runbooks/privileged-role-bootstrap.md`). The command
   records the operator's name and a support ticket reference.
3. The command, and the database trigger as a backstop, refuse if any current holder of the privileged permissions
   exists at or above the scope. A bootstrap is possible only when no approver exists.
4. It is audited as `role.privileged_bootstrap`, with the operator, ticket, reason, target, role and scope, and an
   outbox event notifies the organization administrators.

There is no automatic self-approval for administrators. Once one holder exists, every further privileged assignment
goes through approval.

**Database owner.** The triggers bind the application database role. The schema owner (migrations, the operator
bootstrap) is exempt because it can change the schema anyway. Access to that role is an operational control,
restricted and logged (runbook).

## 11. Database migration strategy

* One forward-only Flyway migration `V2026111x__question_bank.sql`: new tables (§3), RLS policies, composite foreign
  keys, CHECK constraints, triggers (immutability, review validity, approval policy, transition append-only),
  `pg_trgm` extension, indexes (bank/state, taxonomy filters, GIN on `search_text` and tags, trigram on normalized
  statement), grants for the application role (no UPDATE/DELETE on `question_transition`).
* Permission catalogue: six new AVAILABLE permissions; system-role grants per §10 in the same migration
  (`PermissionCatalogConsistencyIT` guards it).
* No existing table altered; no Phase 1 or 2.0 data touched. Reused without modification: `FieldCipher`,
  `ObjectStorage`, `MalwareScanner`, tabular readers (1.6), `AuditLog`, approvals framework (2.0, for policy weakening).
* Dev-only demo data (devdata migration): one sample bank per policy with a few approved questions for the walkthrough.
* Rollback: forward-only, like every migration; a failed deploy is fixed forward. The migration is tested on a copy of
  the dev database with Phase 1 and 2.0 data.

## 12. Screens (staff app, new "Question bank" section)

1. **Question banks** — list; create/edit bank (unit, intended uses, approval policy, defaults).
2. **Bank questions** — search (full text) and filters (type, state, subject, topic, subtopic, difficulty, outcome,
   tag, attribute), counts by state, bulk actions limited to "submit my drafts".
3. **Question editor** — type-specific editors (options with correct marks; true/false; accepted answers and match
   mode; numeric value, precision, tolerance, partial band), content editor with math and images, alt text, marks and
   negative marks, taxonomy, tags, attributes; live candidate preview (no key) and marking preview.
4. **Question detail** — current and approved versions, version list and side-by-side comparison, transition timeline
   with reasons, reviews with checklist, actions per state and policy, key panel (only when allowed; view audited).
5. **Review queue** — "to review" and "to approve", with policy hints (e.g. "needs one more reviewer").
6. **Asset library** — per bank, upload, scan status, thumbnails, alt text, usage.
7. **Taxonomy** — subjects, topics/subtopics, learning outcomes, difficulty levels, tags, attribute definitions.
8. **Approval policies** — institution floors and bank policies; weakening shows the pending approval.
9. **Import** — upload, preview (valid, invalid with reasons, exact and near duplicates), commit, result; templates.

All screens: axe-clean, keyboard operable, responsive (desktop/tablet/mobile), lazy-loaded within the bundle budget.

## 13. Security and functional test plan

**Unit:** each type plug-in (body/key/marking validation, delivery projection, canonical hash); numeric parsing,
rounding and tolerance including boundaries, negative numbers, exponent rejection, BigDecimal precision; short-answer
normalization; content-format parser (all block types, unknown types rejected, limits, deny-listed TeX commands); image
validation (types, header dimensions, bomb guard, re-encode strips metadata).

**Integration (Testcontainers):**
* Lifecycle: every transition in §5.1 including reasons, transition rows and audit events; SUPERSEDED on approval of
  n+1; RETIRED keeps content.
* Immutability: API edits of approved versions refused; direct SQL UPDATE of content/key/assets and DELETE refused by
  triggers; new version leaves old id, content, key and assets byte-identical (hash compare).
* Review invalidation: edit after one or more reviews → back to DRAFT, reviews invalidated; approval with stale review
  hash refused by the service and by the trigger.
* Approval policy: STANDARD (author cannot review/approve), HIGH_STAKES (three distinct people; approver who reviewed
  is refused), stricter institution floor (two reviewers), ADMISSION_EXAM bank cannot be STANDARD (CHECK), weakening
  requires four-eyes approval and is refused while versions are in review; each rule tested via API **and** direct SQL.
* Answer-key negative security matrix: every endpoint (list, search, get, versions, history, transitions, queues,
  assets, import rows, preview, taxonomy) × roles (reader, author of another question, bank manager, reviewer,
  approver, key reader, other institution, other organization, candidate token, API client, unauthenticated) → keys
  present only where §7 allows; key views audited; delivery process 404 for all question paths.
* Leak checks: no plaintext key in database rows (ciphertext only), search vector, Valkey keys/values, application
  log output, error bodies, response headers; `Cache-Control: no-store` on key responses.
* Tenant and unit isolation: another organization and a sibling institution get 404 on every resource including assets
  and imports; cross-bank asset references refused.
* Images: PNG/JPEG accepted; SVG, GIF, HTML-in-PNG polyglot, oversized, bomb dimensions rejected or neutralised;
  EICAR (fake scanner) → INFECTED, never served; PENDING not served; submission blocked until CLEAN and alt text present;
  served headers verified.
* Math: deny-listed commands and oversize formulas rejected server-side; stored content never contains HTML.
* Import: templates per type round-trip; invalid rows with reasons; numeric and math columns; exact duplicates in file
  and in bank; near duplicates with similarity; nothing created before commit; commit idempotent; scan gate fail-closed
  outside development; row and size limits.
* Search and filters: full text, each filter, attribute filters, paging; keys not searchable.
* Performance: 20,000-question bank — search p95 < 300 ms, near-duplicate check per imported row < 50 ms (benchmark
  report).

**Frontend unit:** content renderer produces only text nodes, MathML and `<img>` with alt; XSS payloads in text and
alt text render inert; KaTeX options (`trust: false`, MathML) enforced; editor source parsing.

**E2E (Playwright, real Keycloak):** author creates one question of each type with math and an image → submits →
reviewer reviews → (high-stakes bank) second distinct person approves; author cannot review/approve own; approver who
reviewed cannot approve in a high-stakes bank; edit after review invalidates review; approved edit creates version 2;
version 1 unchanged; approve 2 → 1 superseded; retire with reason; bank manager sees no keys; import with preview and
duplicates; XSS and script payloads in content never execute; axe on all screens; tablet and mobile.

**Regression:** full backend, frontend and E2E suites; staff bundle budget; exam app budget and boundary check (math
renderer not yet loaded in the exam app in 2.1).

## 14. Acceptance criteria

1. All five question types can be created, previewed, reviewed, approved, versioned and retired; numeric marking rules
   (precision, tolerance, partial band) validated and previewable.
2. Approval policies enforced in the service and the database: never self-approval; standard banks author ≠ approver
   (and ≠ reviewers); high-stakes admission banks three distinct people; stricter institution floors honoured; any
   modification after review invalidates the review.
3. Approved versions immutable at API and database level; modifications create a new draft that passes the full
   workflow; version ids permanent; retirement/supersession never alter content.
4. Math renders safely (MathML, no HTML/JS from content); images are scanned, re-encoded, access-controlled per
   institution, previewable and require alt text.
5. Answer keys: separately stored and encrypted; access enforced in API and data-access layers; every view and change
   audited; the negative security matrix passes; no exposure via candidate APIs, exports, search, logs, caches or
   errors; bank management alone grants no key access.
6. Every lifecycle action recorded with actor, timestamp, reason and transition; audit chain verifies.
7. Taxonomy (subject, topic, subtopic, difficulty, outcome, tags, metadata) configurable per institution; questions
   reusable across admissions, academics and placement.
8. Import with validation, preview, exact and near duplicate detection; nothing before commit; idempotent commit.
9. Tenant and unit isolation tests pass for every endpoint and asset.
10. Full backend, frontend and E2E suites green in CI; bundle budgets hold.
11. Docs: ADR-0009 (question versioning, approval policies and answer-key protection), ADR-0010 (content format, math
    and images), runbook, permission catalogue, known issues, decision register, increment record, lifecycle
    demonstration.

## 15. Estimate (reassessed)

| Work | Days |
|---|---|
| Data model, migration, triggers, RLS, policy engine | 4–5 |
| Question types SPI and five types incl. numeric marking preview | 4–5 |
| Content format, math validation, image pipeline (scan, re-encode, storage, serving) | 4–5 |
| Answer-key store, access layers, audit, leak hardening | 2–3 |
| Workflow, reviews, invalidation, transitions, queues | 3 |
| Taxonomy, metadata, search | 3 |
| Import (templates, validation, duplicates, preview, commit) | 3–4 |
| Frontend: 9 screens, content editor with math and images, renderer | 9–11 |
| Security test matrix, integration tests, performance benchmark | 4–5 |
| E2E, accessibility, docs, ADRs, demo, CI, review fixes | 4 |
| P2-08 privileged role assignment through approval, bootstrap, regression tests (added 2026-11-08) | 3–5 |
| **Total** | **43–53 engineering days** |

The increase over the first plan (14–16 days) comes from numeric questions, math and images with scanning,
configurable approval policies with database enforcement, the answer-key access layers and the negative test matrix.
Planning estimate, not a commitment.

## 16. Risks

| Risk | Mitigation |
|---|---|
| KaTeX not approved | Fallback: statements show formulas as source text in 2.1 (stored format unchanged), renderer added later without data migration |
| MathML rendering differences between browsers | Visual checks in Chromium, Firefox and WebKit in E2E; authors see the same renderer as candidates |
| Image pipeline depends on the real scanner (RB-1) | Fail-closed outside development, as for documents; RB-1 remains a production blocker |
| Trigger logic drift from service rules | Same rules tested through both paths; triggers documented in ADR-0009 |
| Answer-key leak through a future endpoint | ArchUnit rule (only `AnswerKeyStore` reads the table), key-bearing DTO marker type checked by a serialization test over all controllers |
| Scope size | Work split into milestones (model+workflow → content+images → import → screens); each milestone keeps CI green |
