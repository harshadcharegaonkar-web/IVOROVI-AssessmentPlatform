# Increment 1.6: student bulk import and data migration

* Status: **Implemented and verified; awaiting product-owner review** (authorized 2026-11-03 with the 1.5 acceptance).
  Increment 1.7 not started.
* Depends on: 1.5 (accepted; follow-ups recorded in `increment-1.5.md`).
* Design: `docs/architecture/08-student-import.md`; operations: `docs/runbooks/student-import.md`; benchmark:
  `docs/performance/student-import-benchmark.md`.

## Scope delivered

| Area | Delivered |
|---|---|
| File formats | XLSX (first visible sheet) and CSV. Server-side type detection (content and extension must agree), 10 MB / 10,000 rows / 100 columns / 1,000 characters per cell (configurable), encoding detection (BOM, strict UTF-8, Windows-1252 fallback; UTF-16), delimiter detection (comma, semicolon, tab), clear errors for unsupported, empty and malformed files. Macro-enabled, binary, legacy and password-protected workbooks refused; formulas never evaluated (a formula cell is a row error); XML external entities disabled; ZIP-bomb limits. JDK only — no spreadsheet library |
| Templates | Standard XLSX template (Students, Instructions, Reference values with the institution's departments, programs, courses, batches, academic years and option codes) and CSV template; sections selectable: personal information, identification, admission, enrolment, guardians |
| Column mapping | Automatic matching on normalised key, label or alias only; ambiguous headings (several candidate fields, or several columns for one field) are left unmapped with an explanation; manual mapping; required and conditional field indicators; unmapped-column warnings; preview of mapped values; saved mappings per institution or organization, reusable at upload |
| Validation | Every row before import: names, date of birth (institution policy can make it mandatory), gender, emails, mobile numbers, student ID format (numbering rule), registration format, roll number; admission (number, date, type, category, status, entry year, program, course, entry semester, transfer rules); enrolment (batch, academic year, semester, enrolment date, department and institution checks); guardians (name, relationship, mobile or email). Academic references resolved inside the import's institution (tenant RLS + subtree), with the importer's department permissions. Row and column references with category, message and recommended correction; messages never contain personal values |
| Duplicates | Within the file and against existing students (identifiers tenant-wide; details within the institution): exact duplicate, possible duplicate (configurable rules: personal email, name + date of birth, name + mobile), conflicting identity, existing student with new records, conflicting academic records. Possible duplicates need an authorized decision (create new / add to existing / skip, by row or category); nothing is merged and existing profiles are never overwritten |
| Modes | All or nothing (no record remains unless every row imports; compensation on failure or cancellation) and valid rows only; every source row gets a final outcome |
| Preview and confirmation | Total, valid, invalid, duplicate, possible duplicate, warnings, students to create, existing students affected, rows not imported; downloadable validation report; explicit acknowledgment; stale previews refused (`expectedRows` + version) |
| Background processing | Outbox → RabbitMQ (`student.import.queued`) → worker, with a sweeper for missed messages and crashed workers; leases with heartbeats; statuses uploaded, queued, validating, ready, processing, completed, partially completed, failed, cancelled; progress, processed/successful/failed/skipped counts; cancellation (validation, before processing, during processing) and retry of system failures; groups of 25 rows per transaction, isolated row by row when one fails |
| Idempotency | Unique job identity; import keys bound to file content (same key + other file → 409); a row's records and outcome commit together (repeated execution, resumed and retried jobs never import a row twice); advisory locks + live duplicate re-check against concurrent imports; unique indexes as last defence |
| History and audit | Import history (job ID, file, institution, uploader, date, mode, total/successful/failed/skipped rows, status) with filters; downloads: original file (clean scan, retention period, personal-data permission), validation report, error report, result report, rows to correct. Audit: upload, rejection, key reuse, mapping, validation, decisions, confirmation (approval), processing, per-student import, completion, failure, rollback, cancellation, retry, report/source/value access, mapping and rule changes, purge |
| Error reports | CSV or XLSX with original row number, column name, error category, description and recommended correction; *Rows to correct* repeats the failed rows with their original columns and an errors column, ready to fix and upload again |
| Security and privacy | Tenant RLS on 4 tables; tenant-prefixed object keys; institution `student.import` + `student.manage`; department permission per row; personal and guardian data need `student.personal.read` (values masked otherwise); malware scan before storage and fail-closed policy; raw row values encrypted (AES-GCM) and purged with source files after the retention period; refused access audited; no personal values in logs, audit details or error messages |
| Screens (12) | Import dashboard, file upload, column mapping, validation, duplicate resolution, preview, confirmation, progress, result summary, error report, history, template management (standard templates, saved mappings, matching rules) |

## Permission

`student.import` — *Import students in bulk* (MANAGE, unit scope), granted to ORG_ADMIN, INSTITUTION_ADMIN and
ADMISSION_OFFICER (`V202611051000`). Catalogue: 41 permissions, 26 available.

## APIs (`/api/v1/student-imports`)

| Endpoint | Purpose |
|---|---|
| `GET /overview`, `GET /` (history, filters, paging) | Dashboard and history |
| `GET /fields?institutionId=`, `GET /template?sections=&format=&institutionId=` | Field catalogue, standard templates |
| `POST /` (multipart: file, institutionId, importKey, mappingTemplateId) | Upload |
| `GET /{id}`, `GET /{id}/sample` | Job (counts, progress, mapping, abilities); first rows for the mapping preview |
| `PUT /{id}/mapping` | Save mapping (optionally as a template) and queue validation |
| `GET /{id}/rows?filter=` | Rows with issues, matches, decisions and outcomes |
| `POST /{id}/decisions`, `POST /{id}/confirm`, `POST /{id}/cancel`, `POST /{id}/retry` | Decisions, confirmation, cancellation, retry |
| `GET /{id}/reports/{validation|errors|result|corrections}?format=`, `GET /{id}/source` | Downloads |
| `GET/POST /mappings`, `PUT/DELETE /mappings/{mappingId}`, `GET /settings`, `PUT /settings/{institutionId}` | Saved mappings and matching rules |

OpenAPI (`docs/api/openapi.json`) and the generated TypeScript client were regenerated.

## Configuration

New: `IVOROVI_IMPORT_MAX_FILE_BYTES`, `IVOROVI_IMPORT_MAX_ROWS`, `IVOROVI_IMPORT_RETENTION_DAYS`,
`IVOROVI_IMPORT_WORKER_ENABLED`, `IVOROVI_IMPORT_CONCURRENCY` (`docs/runbooks/configuration.md`). Multipart envelope
raised to 11 MB.

Migrations: `V202611051000__student_import.sql` (tables, RLS, permission grants, institutional-email index) and
`V202611051100__student_import_lookup.sql` (tenant-restricted lookup function, guardian and enrolment indexes).

## Results

| Check | Result |
|---|---|
| Backend (JUnit + Testcontainers) | **211 tests**, 0 failed — incl. `StudentImportIT` (19: templates, CSV/XLSX upload and automatic mapping, ambiguous columns, rejected files, saved mappings; row and reference validation with valid-rows-only import, reports and audit without personal values; formula cells; guardian contact rule; all-or-nothing success and rollback; duplicates within the file, against existing students and across imports with decisions; matching rules and date-of-birth policy; import keys; repeated execution and crashed workers; concurrent imports of the same students; system-failure retry and cancellation; 1,000-row background import; isolation across tenants and institutions; retention purge) and `TabularFileTest` (11: encodings, delimiters, quoting, row numbers, limits, detection, shared strings, dates, formulas, macros, ZIP bomb, XXE, writers, formula injection); all 1.0–1.5 suites |
| Smoke test with real Keycloak tokens | **76 passed** (adds template download, faculty denied, CSV upload, import-key idempotency, cross-tenant 404, mapping, background validation through RabbitMQ, error report, all-or-nothing refused with invalid rows, confirmation, background import, imported student searchable, cleanup) |
| Frontend unit tests / typecheck / lint | **39** web tests (adds `importModel.test.ts`), 11 UI, 3 API client; clean |
| Bundle | initial **155.8 kB gzip** (≤ 180 kB, enforced); every import screen lazy (largest import chunk 4.3 kB gzip) |
| Playwright 1440×900 / 1024×768 / 390×844 | **86 passed** (5 new in `imports.spec.ts`: upload → map → validate → preview → confirm → background import → result, report download, error report, history; templates; no access without the permission; no horizontal scrolling on every import screen at tablet and mobile) |
| Accessibility | axe WCAG 2.1 AA: no serious/critical violations on dashboard, upload, mapping, validation, preview, confirmation, progress, result, error report, history and templates |
| Performance | 100 / 1,000 / 5,000 / 10,000 rows: validation 2.3 / 3.4 / 7.6 / 11.9 s, processing 5.6 / 28.0 / 138.6 / 268.5 s (≈ 36 rows/s), peak heap ≤ 453 MB, 0 duplicates, against an organization growing from 16,200 to 36,400 students; crash during processing recovered without duplicates (`docs/performance/student-import-benchmark.md`) |
| Migration | Development database upgraded to `202611051000` (0.43 s) and `202611051100` (0.04 s); 4 new tables with RLS, 1 lookup function, 3 additive indexes; existing records unchanged (Meridian demonstration figures verified after the upgrade) |
| Demonstration data | `scripts/demo-data.sh rebuild` now imports 16 students through the real import (all or nothing) and leaves a file with deliberate problems awaiting decisions; every dashboard figure matches its specification (36 students) |

## Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| 1 | XLSX upload | `FilesTemplatesAndMapping.csvAndXlsxUploads…`, `TabularFileTest`, demonstration MCC file |
| 2 | CSV upload | Same; smoke test; Playwright |
| 3 | Standard templates download | `standardTemplatesDownloadAsXlsxAndCsv`; Playwright templates test |
| 4 | Institution mappings saved and reused | `savedMappingsAreReusedForLaterFiles` |
| 5 | Column mapping | Automatic, ambiguous and manual mapping tests; Playwright mapping step |
| 6 | Row-level validation | `rowLevelAndReferenceValidation…`, `formulaCells…`, `guardiansNeedAMobileOrAnEmail` |
| 7 | Academic references validated | Unknown batch of another college, course/batch/year rules in the same tests |
| 8 | Duplicates within and across imports | `Duplicates.*` (in-file, existing, cross-import), `concurrentImports…` |
| 9 | All-or-nothing | `AllOrNothing.*` (success and full rollback, matched student restored) |
| 10 | Valid rows only | `rowLevelAndReferenceValidationThenValidRowsOnlyImport`, Playwright |
| 11 | Preview and confirmation | Stale preview and missing acknowledgment refused; Playwright preview/confirm |
| 12 | Asynchronous large imports | Outbox → RabbitMQ worker (smoke, Playwright), 10,000-row benchmark |
| 13 | Accurate progress and status | Progress assertions in IT and Playwright; benchmark counts vs database |
| 14 | Retries do not duplicate | `repeatedExecutionAndCrashedWorkers…`, benchmark crash recovery |
| 15 | Import history | History assertions in IT, Playwright history screen |
| 16 | Error reports | Error, corrections and result reports in IT, smoke and Playwright downloads |
| 17 | Cross-tenant access blocked | `otherTenantsAndOtherInstitutions…`, smoke |
| 18 | All operations audited | Audit assertions (upload, mapping, validation, decision, confirmation, processing, per student, completion, failure, rollback, retry, cancel, downloads, settings, purge) |
| 19 | Privacy controls | Encrypted row values, masked values without permission, audit/log scan, purge test, matched students hidden outside scope |
| 20 | Backend and frontend tests | 211 backend, 53 frontend unit tests |
| 21 | Real Keycloak authentication | Smoke test (76) |
| 22 | Playwright desktop/tablet/mobile | 86 passed |
| 23 | Performance against agreed limits | Benchmark (10 MB, 10,000 rows) |
| 24 | No regressions 1.0–1.5 | Full backend, Playwright and smoke suites |
| 25 | GitHub Actions | Run 41702305617 (`e5031d3`): security scan and backend green; the frontend job hung in GitHub's runner set-up and could not be cancelled. All jobs re-run on the documentation commit that follows (run ID in the review message and `tracker.md`) |

## Defects found during verification

* Refusal audits (rejected files, reused import keys) were rolled back with the request; they are now written in their
  own transaction. The same pattern in the accepted 1.5 document upload refusal is recorded as KI-53 (not changed).
* Imported enrolments with historical dates failed because a new profile's first status took effect today; the
  profile's first status now takes effect on the admission date (historical dates preserved). For existing students
  the enrolment takes effect no earlier than their current status date (validation warns).
* Guardian rows without a mobile number or email passed validation and failed at processing; validation now requires
  one, like the guardian service.
* Classification precedence: a conflict with a stored student now outranks a conflict within the file, and stored
  possible duplicates outrank in-file ones (more informative messages).
* **Performance (benchmark):** per-row duplicate re-checks scanned the student table because expression indexes are not
  usable under RLS; throughput fell from 31 to 14 rows/s as the table grew to 16,000 students. Fixed with the
  tenant-restricted lookup function `student_import_candidates` and two additive indexes (guardians and enrolments by
  student/admission) — throughput no longer depends on the table size.
* **Pickup latency (benchmark):** a large import produces more outbox events than the publisher's configured rate
  (100 per second), so a later job's queued message waited behind the backlog until the sweeper (≤ 15 s). The requesting
  instance now starts the job right after commit; RabbitMQ and the sweeper remain for other instances and recovery.
  The publisher backlog itself is platform behaviour (KI-58).
* Accessibility: step indicator contrast, a focusable scroll region for the mapped-value preview, tab panels for row
  filters and muted text on tinted backgrounds were corrected after axe findings.

## Not delivered / deviations (see known issues)

* ClamAV deployment remains a release blocker (KI-23); without a scanner, development allows imports but the original
  file cannot be downloaded (KI-55).
* Throughput about 36 rows/s per job because every row uses the accepted student services (KI-52).
* Legacy IDs under generated numbering need a temporary rule change (KI-56); deactivated visibility during all-or-
  nothing processing (KI-57); email-only possible duplicates are not re-checked at processing time (KI-54).
* Excluded as specified: admissions workflow, screening and merit lists, examinations, assessment evaluation, bulk
  account provisioning, cross-institution transfers, automatic merging.

Evidence: `docs/design/evidence/1.6/` (screenshots at 1440×900, 1024×768 and 390×844 from the Meridian demonstration
organization; synthetic data only); sample files `docs/samples/student-import/`.
