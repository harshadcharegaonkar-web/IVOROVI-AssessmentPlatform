# Increment 2.1 — Question bank: implementation record

Status: **Accepted** by the product owner on 2026-11-08, on the basis of this report, CI run **42466656418**
(commit `b83aa42`) and follow-up CI run **42469900842** (commit `b45bfb7`), all jobs green. Acceptance is not a
production authorization: the release blockers stay open. Accepted with follow-ups KI-75 (search performance targets),
KI-77 (manual accessibility validation), KI-78 (real-device Safari) and the root cause of the flaky exam-app test
(KI-79). KI-77 and KI-78 are required before production examination use.

CI evidence (run 42466656418): backend 407 tests; frontend; security scan; end-to-end web suite 115/115 including the
mathematics test in Chromium desktop, Firefox, WebKit/Safari, Edge, mobile Chrome and mobile Safari; exam app 7 passed
plus 1 existing 2.0 test that passed only on retry (root cause in KI-79; a retry is not accepted as evidence of
reliability). Follow-up commit `b45bfb7`: visible labels for three checkboxes in the editor, taxonomy and import screens.

Authorized 2026-11-08: plan `increment-2.1-plan.md`, final approval with KaTeX,
image, governance, numeric, versioning and security-testing requirements, and the permission-conflict resolution P2-08
(Option 2).

**No production-readiness claim.** The release blockers in `../runbooks/production-readiness.md` stay open. Among them
is the malware scanner, which is still required before real documents or images are accepted. Increment 2.2 has not
been started.

## 1. What was built

| Area | Delivered |
|---|---|
| Banks | Institution- or organization-scoped banks, with intended uses. Admission banks are always high-stakes. Policy floors per institution (stricter only). Weakening a bank policy or floor goes through four-eyes approval |
| Question types | Single and multiple choice (all-or-nothing or partial), true/false, short answer (exact, case-insensitive or normalized), numeric. Types are an SPI (`QuestionType`); adding a type needs no storage change |
| Numeric evaluation | Exact decimals (BigDecimal, no floating point); absolute or percentage tolerance, inclusive; precision 0–10 with round-half-up or rejection of extra decimals; negative values; scientific notation; up to 5 equivalent answers; optional wider partial-marks band |
| Content and mathematics | `ivorovi.content.v1` (never HTML). KaTeX 0.16.47, MathML only, `trust: false`, server and client TeX rules, MathML allowlist sanitizer, lazy self-hosted chunk, documented fallback (ADR-0010) |
| Images | Magic-byte check; 5 MB and 4096² limits; 15 MB per question; quarantine and scan (fail closed); re-encoding without metadata; quotas; access-checked serving with hardened headers; 30-day cleanup only for unlinked images |
| Workflow | DRAFT → IN_REVIEW → REVIEWED → APPROVED → SUPERSEDED or RETIRED. Reviews are bound to a content hash, and any modification invalidates them. Service and database enforce: author never reviews or approves; enough valid reviews; distinct approver where required. Append-only transitions; review and approval queues |
| Versioning | Approved versions are immutable (database allowlist guard). New versions are drafts and leave the approved version in use. Permanent references (`QuestionReferences`) for assessments from 2.2; referenced or submitted versions cannot be deleted |
| Answer keys | Separate encrypted store, read only by `AnswerKeyStore`; access by version state and permission. `question.bank.manage` never grants key access. Dedicated `no-store` endpoint; every view and refusal audited; no keys in responses, search, import rows, logs, caches or errors. API clients cannot hold question scopes |
| Taxonomy | Subjects, topics and subtopics, learning outcomes (Bloom), difficulty levels (5 defaults), managed or free tags, institution metadata fields (required fields gate submission), marks and negative marks. Entries are deactivated, never deleted |
| Search | Full text over statements and options, substring match, filters by state, type, subject, topic, subtopic, difficulty, outcome, tag, metadata field and status |
| Import | CSV/XLSX, scanned first. Row validation through the editor's rules. Exact duplicates within the file and the bank; near duplicates (trigram ≥ 0.85). Encrypted row keys until commit. The uploader commits; commit is idempotent and creates drafts |
| Privileged roles (P2-08) | Administrators hold read, author, review and bank management. Roles with approve or key-read permissions are assigned only via four-eyes approval by a current holder at the scope, with live checks at approval and activation and invalidation on change. Database guard on `role_assignment` and `role`. Operator bootstrap for the first holder (runbook). Revocation stays possible for administrators |
| Screens (9) | Banks; bank with search and filters; editor (source with live preview, images, type-specific key, classification); question detail (versions, comparison with the approved version, reviews, transitions, audit history, key on request, marking preview); review and approval queue; image library with scan state and quota; taxonomy; policies and quotas; import (template, preview, commit). Role-assignment dialog shows "Request approval" for privileged roles |

Phase 1 and 2.0 code was changed only where a documented dependency required it:
- `ApiClientService.EXCLUDED`: question scopes excluded from API clients.
- `AssignmentService`, `RoleService`, `UserController`: P2-08.
- `ApprovalService` and `ApprovalHandler`: two backwards-compatible extensions for P2-08 — a request where the
  requester need not hold the permission, and a handler eligibility hook with an invalidation path.
- `IntegrationTest`: the cleanup job is off in tests.
- `PeopleDialogs`: approval notice.
- Navigation and router.
- Web `styles.css`: math font stack.

## 2. Migrations

| Migration | Content | Notes |
|---|---|---|
| `V202611121000__question_bank` | 20 tables, all with RLS. Policy function and floors; triggers for version immutability, review invalidation and binding, approval policy, draft-only keys and image links, permanent references, image deletion, append-only transitions. Permission grants (P2-07/P2-08). Privileged-assignment guard (`holds_permission`, `role_assignment_privileged_guard`, `role_privilege_gain_guard`, columns `approval_request_id` and `bootstrap_reference`) | New tables only, plus two columns on `role_assignment` and grants on system roles. Existing EXAM_CONTROLLER assignments keep their roles and gain the question permissions. Applied in 1.1 s to the populated local development database |
| `V202611121001__question_near_duplicate` | SECURITY DEFINER near-duplicate function (same tenant predicate as RLS, bank-scoped, returns id and score only) | Performance fix found by the benchmark |

## 3. Tests

| Suite | Result |
|---|---|
| Backend (all modules, Testcontainers) | **407 tests, 0 failures**, of which 125 are new: unit 86 (numeric boundaries and tolerance 42, content/TeX injection 36, image inspection 5, choice types 3); question-bank ITs 31 (`QuestionLifecycleIT` 11, `AnswerKeySecurityIT` 6, `QuestionAssetIT` 7, `QuestionImportIT` 4, `QuestionIsolationIT` 3); `PrivilegedRoleAssignmentIT` 8. Includes `PermissionCatalogConsistencyIT`, `ModularityTest`, `TenantIsolationIT` (RLS on every new table) and the 4 `UserManagementIT` tests that the first permission matrix had broken (KI-74) |
| Frontend unit | 92 tests (content renderer 31, web 39, ui 11, exam 8, api-client 3). Lint and typecheck clean |
| Bundles | Web initial 159.5 kB gzip (≤ 180). KaTeX lazy chunk 74.6 kB (own limit ≤ 80, L-2.1-2 pending); never in the initial payload. Exam app 88.0 kB (≤ 120); boundary check clean |
| E2E (local, Chromium desktop and Pixel 7) | `questions.spec.ts` 9/9: demo items 1–7 and 9–11, plus `@math` on desktop and mobile. Full suite 109 passed. Two existing Phase 1 tests failed locally because of years of accumulated E2E data in the local database (students: academic years now reach 2122; people: a slow page that passes on rerun). CI runs on a fresh stack and is authoritative |
| E2E cross-browser (CI run 42466656418) | `@math` passed in Firefox, WebKit (Safari engine), Edge, mobile Chrome and mobile Safari (screenshots in the `math-rendering-screenshots` artifact). The two Phase 1 tests that failed locally passed in CI on a fresh stack |

## 4. Security evidence (per approval §6)

| Requirement | Evidence |
|---|---|
| Unauthorized staff via API | `AnswerKeySecurityIT` matrix (author, other faculty, reviewer, approver, administrators, bank-manager-only role, student) × DRAFT, IN_REVIEW, APPROVED; `QuestionLifecycleIT.facultyAndDesigners…`; import, asset and taxonomy permission tests |
| Cross-institution | Matrix (404 for the other college); `QuestionIsolationIT` (every endpoint 404; no queue entries; cannot create banks elsewhere); cross-institution privileged assignment refused (`PrivilegedRoleAssignmentIT`) |
| Cross-organization | API 404 and RLS row counts zero (`QuestionIsolationIT`); near-duplicate function returns nothing for another organization or with no tenant |
| Candidates | Candidate tokens get 401 on staff endpoints, including keys and images; no question handler is mapped under `/api/v1/delivery` |
| API clients | Question scopes not offered and refused; clients with other scopes get 401/403 on every question endpoint |
| Import and image access | Upload, read, rows and commit per institution; commit by the uploader only; images only for readers of the bank (others 404, audited) |
| Mathematical-content injection | Server: 19 refused TeX inputs, HTML as text, unknown keys and blocks, direction overrides. Client: 11 attack inputs refused before rendering, sanitizer test, `<script>` text never executed (E2E dialog counter) |
| Unauthorized image retrieval | `QuestionAssetIT.imagesAreReadableOnlyByReadersOfTheirBank` |
| Scanner unavailable or failing | Images: ERROR state, never served, submission blocked; imports: 503 and 400, audited; production without a scanner fails closed (unit test) |
| Key exposure via logs, exports, search, caches, errors | `AnswerKeySecurityIT.theKeyNeverLeaks…`: every other response for four roles, the audit export, full-text search, database plaintext (versions, transitions, reviews, audit, outbox), every Valkey key, error responses, captured logs, `Cache-Control: no-store`. No question export exists in 2.1 |
| Governance | DB-level refusal of self-approval, too few reviews, a reviewing approver in high-stakes banks, content change in review, stale review hashes, edits and deletes of approved versions, deletion of referenced versions and references |
| Privileged assignment (P2-08) | `PrivilegedRoleAssignmentIT`: administrators still assign Faculty and Designer; Exam Controller only after approval; self-approval and approval of one's own elevation refused; unauthorized and cross-institution approvers refused; revoked approver or requester invalidates; role changes invalidate; direct database writes refused; bootstrap only when no holder exists, audited with operator and ticket |

## 5. Performance

See `../performance/question-bank-benchmark.md`.
- 20,000-question bank: search p95 129–142 ms (target < 300 ms); 226–299 ms when the organization holds 60,000
  questions (KI-75).
- Near-duplicate check < 40 ms per row after the fix (target < 50 ms).
- Creation p95 55 ms.

## 6. Decisions and approvals

- L-2.1-1 (KaTeX) approved. P2-08 decided. L-2.1-2 (KaTeX chunk budget ≤ 80 kB gzip) approved 2026-11-08 with conditions (decision register).

## 7. Known issues

- New: KI-74 (permission-matrix regression; Fixed by P2-08, verified in CI run 42466656418), KI-75 (search under RLS),
  KI-76 (math font), KI-77 (manual screen-reader testing), KI-78 (real Safari devices).
- All production blockers remain open.

## 8. Demonstration (approval §9)

| # | Item | Where |
|---|---|---|
| 1 | Institution-scoped bank | E2E step 1 (UI); `QuestionIsolationIT` |
| 2 | MCQ, true/false, short answer, numeric | E2E step 2–4; `QuestionLifecycleIT.standardWorkflowWithEveryQuestionType` |
| 3 | Mathematics with KaTeX | E2E step 2–4 and `@math` (eight expression families, all browsers in CI) |
| 4 | Image added and displayed securely | E2E step 2–4; `QuestionAssetIT` |
| 5 | Submission for review | E2E step 5–6 |
| 6 | Independent review and approval | E2E step 5–6 (three different people); `QuestionLifecycleIT` high-stakes tests |
| 7 | New version leaves the approved one unchanged | E2E step 7; `QuestionLifecycleIT.approvedVersionsAreImmutable…` |
| 8 | Versions referenced by assessments preserved | `QuestionLifecycleIT.referencedVersionsArePreserved…` (assessments arrive in 2.2; the reference API and database guarantees exist now) |
| 9 | Unauthorized answer-key access prevented | E2E step 9; `AnswerKeySecurityIT` |
| 10 | Import and duplicates | E2E step 10; `QuestionImportIT` |
| 11 | Tenant isolation and permissions | E2E step 11; `QuestionIsolationIT`; `PrivilegedRoleAssignmentIT` |
