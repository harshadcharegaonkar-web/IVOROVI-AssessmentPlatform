# Student bulk import and data migration (increment 1.6)

A reusable import capability for moving existing student records into Ivorovi. It reuses the accepted 1.5 student
services for every record it creates, so validation, scope permissions, encryption, lifecycle rules, events and audit
are identical to records entered by hand.

## Flow and states

```
upload ─► UPLOADED ─(mapping)─► QUEUED ─► VALIDATING ─► READY ─(decisions, confirm)─► QUEUED ─► PROCESSING ─► COMPLETED
   │                                          │            │                                       │          PARTIALLY_COMPLETED
   └ rejected (type, macros, malware,         └ FAILED     └ CANCELLED                              └ FAILED (rolled back if all-or-nothing)
     size, rows, malformed)                     (retry if system error)                               CANCELLED
```

| Step | Where | What happens |
|---|---|---|
| Upload | request | Institution + `student.import` and `student.manage` checked; fail-closed scanner policy (STAGING/PRODUCTION need a scanner); content-type detection (ZIP/OLE magic + extension must agree); macro-enabled, legacy and encrypted workbooks refused; malware scan (clamd adapter); full streaming parse to count rows and enforce limits; file stored at `t/{tenant}/imports/students/{job}/source.*`; suggested mapping |
| Mapping | request | Field catalogue (`ImportField`): headers matched on normalised key/label/alias only; several candidates or several columns for one field → left unmapped with a note (never silent); required and conditional fields enforced; personal/guardian fields need `student.personal.read`; mapping can be saved as a reusable template |
| Validation | background | Streaming parse; per row: typed values, formats, references resolved inside the institution's subtree, permissions on the course's department, institution policy (date of birth), duplicate classification; rows stored with encrypted raw cells (`cells_enc`), issues (row/column/category/message/hint, never values), match and plan; inserts in batches of 500 |
| Decisions | request | Possible duplicates need `CREATE_NEW`, `ADD_TO_EXISTING` (only for a matched existing student) or `SKIP`; by row or by category; recorded with user and time; never merges |
| Confirmation | request | Mode chosen; `expectedRows` and job `version` must match the preview (stale previews are refused); all decisions made; all-or-nothing refused when invalid/conflicting rows exist; non-imported rows get their final outcome immediately |
| Processing | background | Rows in groups of 25 per transaction; a failing group is retried row by row to isolate the failure; each row re-parsed, re-validated, advisory-locked on its identifiers and name key and re-checked against the live database; records created through `StudentService`, `AdmissionService`, `EnrolmentService`, `GuardianService` and lifecycle transitions; outcome written in the same transaction |

## Background processing

* **Messaging:** confirming or mapping appends `student.import.queued` to the transactional outbox; the existing
  publisher delivers it to RabbitMQ (`ivorovi.events`, routing key `student.import.queued`) and the queue
  `ivorovi.student-imports` triggers a worker on any instance. The instance that handled the request also starts the job
  right after commit (local event), so a backlog in the outbox never delays a job. A sweeper (every 15 s) picks up
  anything missed: lost messages, broker outages, crashed workers.
* **Leases:** a worker claims a job with `lease_owner`/`lease_until` (2 min, extended after every batch). Only one
  worker processes a job; when a worker dies the lease expires and another instance resumes. After 3 failed attempts the
  job is marked failed (system).
* **Identity:** each phase runs as the user who requested it (mapping → validation, confirmation → processing). The
  principal is resolved fresh from the database, so revoked permissions or deactivated accounts stop the work.
* **Transactions:** no long transaction spans the file. Validation commits every 500 rows; processing commits every
  25 rows (or every row when isolating a failure).

## Import semantics

| Mode | Guarantee | Mechanism |
|---|---|---|
| All or nothing | No business record of the job remains unless every planned row imported | Students touched by the job are deactivated (unchangeable through the API) until the last row commits, then published with one `UPDATE`. On any row failure or cancellation the job compensates newest-first: students it created are removed with their records; matched existing students lose exactly the records created in the job's transaction (`created_at = processed_tx`) and get their prior status back. Audit and outbox keep the history (`student.import.rolled_back`) |
| Valid rows only | Each valid row imports independently | Invalid, conflicting, duplicate and skipped rows are reported; a row failing at processing time is recorded with its reason and the job continues |

Both modes give every source row a final outcome: `IMPORTED`, `UPDATED`, `SKIPPED`, `FAILED`, `ROLLED_BACK` or
`NOT_PROCESSED`.

## Idempotency and concurrency

* Every job has a UUID. An optional client `importKey` identifies one source file: the same key and content returns
  the same job; the same key with other content is refused (`409 import_key_reused`, audited).
* A row's records and its outcome commit together; the outcome update is conditional on `PENDING`, so a duplicate
  delivery, a retry or a resumed job never imports a row twice.
* Duplicate lookups per row go through `student_import_candidates(...)` (V202611051100): a `SECURITY DEFINER` function
  that filters on `current_tenant_id()` itself and returns only matching student ids. It exists because PostgreSQL
  cannot use the `lower(...)` expression indexes for user predicates under row-level security (`lower()` is not
  leakproof); without it every row scanned the student table. Students are then read by primary key under RLS.
* Concurrent imports: transaction-scoped advisory locks on the row's student, registration and admission numbers,
  emails and name key serialise competing rows; the live re-check then classifies the second as already present. Unique
  indexes on student and registration numbers (tenant) and admission numbers (institution) remain the last line of
  defence.
* Retry is offered only for system failures; completed rows are kept (rolled-back all-or-nothing jobs start again).

## Duplicate detection

| Category | Meaning | Status | Decision |
|---|---|---|---|
| `IN_FILE_DUPLICATE` | Earlier row with the same identifier and identical values / different values | DUPLICATE / CONFLICT | — (skipped) |
| `EXACT_DUPLICATE` | Existing student, same identity, nothing new in the row | DUPLICATE | — (skipped) |
| `EXISTING_NEW_ENROLMENT` | Existing student, same identity; row adds admission, enrolment, guardians or activation | NEEDS_DECISION | add to existing / skip |
| `CONFLICTING_IDENTITY` | Identifier belongs to a student with another name or date of birth, to another institution, outside the importer's scope, or identifiers point to different students | CONFLICT | — |
| `CONFLICTING_ACADEMIC` | Existing student with other admission or current enrolment (transfers stay on the profile), deactivated, or a final status | CONFLICT | — |
| `POTENTIAL_DUPLICATE` | No identifier match but the configurable rules match: personal email, name + date of birth, name + mobile | NEEDS_DECISION | create new / skip |
| `IN_FILE_POTENTIAL` | An earlier row may be the same person | NEEDS_DECISION | create new / skip |

Identifiers (student, registration and admission numbers, institutional email) always identify a student. The rules
per university/college live in `student_import_settings`; profile details of existing students are never overwritten.

## File handling (platform `tabular` package, JDK only)

| Concern | Implementation |
|---|---|
| CSV | RFC 4180; BOM (UTF-8/UTF-16) or strict UTF-8 check with Windows-1252 fallback; comma/semicolon/tab detection; row numbers as a spreadsheet shows them |
| XLSX | ZIP + StAX streaming of the first visible sheet; shared strings, inline strings, date styles (1900/1904), plain numbers (no exponent); macros (VBA project, macro-enabled type, ActiveX) refused; DTD/external entities disabled; ZIP-bomb limits on total size and compression ratio |
| Formulas | Never evaluated; a formula cell is a validation error ("paste values") |
| Limits | 10 MB, 10,000 rows, 100 columns, 1,000 characters per cell (configurable) |
| Output | Streaming CSV (BOM, formula-injection neutralised) and XLSX (inline strings) for templates and reports |

## Data model (migration `V202611051000__student_import.sql`)

| Table | Purpose |
|---|---|
| `student_import_job` | File metadata (name, format, size, SHA-256, encoding, sheet, scan status, object path), headers, mapping, status/phase/mode, validation counts, progress counts, lease, attempts, failure, actors and timestamps, purge marker |
| `student_import_row` | Row number, encrypted raw cells, display name and identifiers, validation status, issues (JSON), match (category, student, row, reason), plan, decision (by/at), outcome (code, message), created/updated student, compensation data (`processed_tx`, `prior_state`) |
| `student_import_mapping` | Saved column mappings per institution or organization |
| `student_import_settings` | Matching rules and date-of-birth policy per university/college |

All four tables have tenant RLS. An index on `student (tenant_id, institutional_email)` supports duplicate checks.

## Retention

Source files and raw row values (`cells_enc`) are purged `IVOROVI_IMPORT_RETENTION_DAYS` (default 30) after a job
finishes; abandoned uploads and validated-but-unconfirmed jobs are cancelled and purged after the same period. Counts,
outcomes, issues and audit records stay.

## Performance

Validation streams the file and keeps only identifier maps in memory. Processing costs are dominated by the accepted
student services (about 20 statements, 6–7 audit records and outbox events per fully specified row). Measured figures:
`docs/performance/student-import-benchmark.md`.
