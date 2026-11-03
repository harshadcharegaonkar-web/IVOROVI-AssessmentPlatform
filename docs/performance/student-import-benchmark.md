# Student import benchmark (increment 1.6)

Measured 2026-11-03 with `scripts/benchmark/import_benchmark.py` against the local stack: API jar (Java 21,
default heap settings) on the host, PostgreSQL, Valkey, RabbitMQ and Keycloak in Docker Compose. Machine: 4 vCPU,
15 GB RAM, shared by all services. Real Keycloak tokens. A separate organization ("Import Benchmark Org") is used, so
the demonstration and test organizations are untouched. Raw results: `student-import-benchmark.json`.

**Data:** synthetic rows with every section filled: student and registration numbers, names, date of birth, gender,
personal email, mobile, admission (number, date), batch, semester, status (¾ active), one guardian with relationship
and mobile. Each row creates a student, an admission, an enrolment, a guardian, lifecycle transitions and (for ¾ of the
rows) an activation: about 32 database rows and 6–7 audit records per student. Mode: valid rows only.

## Results (after the fixes below)

The organization already held 16,200 students when this run started and 36,400 when it ended, so the figures include
duplicate detection against a large existing table.

| Rows | File | Validation | Processing | Rows/s | Imported / in DB | Duplicates | API peak RSS | Peak heap used | DB commits | DB rows inserted | DB blocks read from disk |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 20 kB | 2.3 s | 5.6 s | 18.0 | 100 / 100 | 0 | 647 MB | 212 MB | 298 | 3,208 | 11 |
| 1,000 | 197 kB | 3.4 s | 28.0 s | 35.7 | 1,000 / 1,000 | 0 | 926 MB | 415 MB | 2,427 | 31,275 | 3,558 |
| 5,000 | 993 kB | 7.6 s | 138.6 s | 36.1 | 5,000 / 5,000 | 0 | 935 MB | 411 MB | 11,962 | 172,516 | 32,398 |
| 10,000 | 1,988 kB | 11.9 s | 268.5 s | 37.2 | 10,000 / 10,000 | 0 | 1,007 MB | 453 MB | 23,545 | 320,208 | 74,759 |

* *Validation*: from saving the mapping until the job is ready (background parse, validation, duplicate index over all
  existing students of the institution, row inserts). *Processing*: from confirmation until the job finished
  (100 rows: includes worker start-up of a few seconds).
* *Imported / in DB*: rows reported imported vs. students found in the database afterwards; *Duplicates*: students
  sharing a registration number (must be 0).
* Memory: resident memory and heap in use of the whole API process (sampled every second with `jcmd GC.heap_info`).
  Heap stays below 0.5 GB at 10,000 rows: the file is streamed and only identifier maps are held.
* Database: deltas of `pg_stat_database` for the run (all activity of the stack, including polling).

## Failure recovery

| Scenario | Result |
|---|---|
| API process killed (`kill -9`) after 1,000 of 3,000 rows had been processed; restarted 45 s later | The job's lease expired (2 min) and the restarted API resumed it (attempt 2). **3,000 imported, 3,000 students in the database, 0 duplicates.** Total processing 217.5 s including the outage and lease wait |
| Duplicate delivery, re-queued completed job, lost outcome of a row, concurrent imports of the same file | Covered by `StudentImportIT.ProcessingIdempotencyAndRecovery` (no duplicate students in any case) |
| Failing row in all-or-nothing mode | `StudentImportIT.AllOrNothing`: every committed row compensated, matched student restored |

## Before the fixes (same script, same machine)

| Rows | Validation | Processing | Rows/s | Note |
|---|---|---|---|---|
| 100 | 1.0 s | 4.1 s | 24.7 | 0 existing students |
| 1,000 | 5.1 s | 32.4 s | 30.9 | 100 existing |
| 5,000 | 17.2 s | 228.7 s | 21.9 | 1,100 existing |
| 10,000 | — | 713 s | 14.0 | 6,200 existing (measured from the job's timestamps) |

Two defects were found and fixed:

1. **Duplicate re-check scanned the student table.** Under row-level security PostgreSQL does not use the
   `lower(student_no)` / `lower(registration_no)` / name expression indexes for user predicates (`lower()` is not
   leakproof), so every row's live duplicate check read the whole table (≈ 40 ms per row at 25,000 students). Fixed with
   `student_import_candidates(...)`, a tenant-restricted `SECURITY DEFINER` lookup that uses the indexes, plus indexes on
   guardians and enrolments by student/admission. Throughput no longer falls as the organization grows.
2. **Job start waited for the outbox backlog.** A large import produces more integration events than the publisher's
   configured rate (100 per second), so a new job's queued message waited until the sweeper. The requesting instance now
   starts the job right after commit. The publisher backlog itself is recorded as KI-58.

## Assessment against the agreed limits

| Limit | Value | Status |
|---|---|---|
| File size | 10 MB (10,000 fully filled rows ≈ 2 MB) | Met |
| Rows per file | 10,000 | Met: 10,000 rows validated in 12 s and imported in 4.5 min, memory bounded |
| Throughput | ≈ 36 rows/s per job on this machine; 2 jobs per instance | Accepted (KI-52): every row runs through the accepted student services with full validation, events and audit |
| Recovery | Resume after a crash without duplicates | Met |

Re-run: `python3 scripts/benchmark/import_benchmark.py --sizes 100,1000,5000,10000 --recovery 3000 --out docs/performance/student-import-benchmark.json`
(local stack running; development only).
