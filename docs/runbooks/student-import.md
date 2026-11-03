# Migrating existing students with the bulk import

For administrators holding **Import students in bulk** (`student.import`) and **Manage students** at a university or
college. Personal and guardian columns also need **View student personal details**.

## Before the first import

1. **Academic structure first.** Programs, courses, batches and academic years must exist (Academics). The import only
   refers to them by code; it never creates them.
2. **Student numbering.** If the institution *generates* student IDs, new students cannot bring their legacy IDs. To keep
   legacy IDs, set the institution's numbering rule to *entered IDs* with a matching format (Student settings) for the
   duration of the migration, then switch back (KI-56). Registration numbers must match the registration format.
3. **Matching rules.** Review *Students → Student Imports → Templates and rules*: possible duplicates are detected by
   personal email, name + date of birth and name + mobile (each can be switched off), and the date of birth can be made
   mandatory.
4. **Template.** Download the Excel template with the institution's reference codes. One row per student; the first row
   holds the headings. Format ID and phone columns as text in the spreadsheet so leading zeros are kept.

## Running an import

1. *New import*: choose the institution and the file (.xlsx or .csv, ≤ 10 MB, ≤ 10,000 rows). Optionally choose a saved
   mapping.
2. *Map columns*: check every column; ambiguous headings are never mapped automatically. Save the mapping if the same
   export will be imported again.
3. *Validation* runs in the background. Correct the source file and upload it again when there are many errors; download
   *Rows to correct* (.xlsx) to get exactly the failed rows with their errors.
4. *Duplicates*: decide for every possible duplicate. Existing students can receive the row's admission, enrolment and
   guardians; their profile details are never overwritten. Nothing is merged.
5. *Preview* and *Confirm*: choose **All or nothing** for migrations that must be complete (no invalid rows allowed) or
   **Valid rows only** for incremental loads. Confirm.
6. *Progress*: the import runs in the background; it can be cancelled (all-or-nothing imports are then rolled back).
7. *Result*: download the result and error reports. Rows not imported can be corrected and uploaded as a new file;
   duplicates of rows already imported are detected and skipped.

## Operations

| Situation | Action |
|---|---|
| Job stays *Queued* | Check that RabbitMQ is running and `IVOROVI_IMPORT_WORKER_ENABLED` is true on at least one API instance. The sweeper starts queued jobs within 15 s even without the broker |
| API restarted during an import | Nothing to do: the job's lease expires after 2 minutes and the job resumes; completed rows are not imported again |
| Job *Failed* (system) | Use *Retry* (completed rows are kept). Repeated failures: check the API log for the import ID (no personal values are logged) |
| Job *Failed* (data) in all-or-nothing | Everything was rolled back; correct the file (error report) and upload it again |
| Uploads refused with "scanning unavailable" | STAGING/PRODUCTION without a malware scanner (fail closed, KI-23): configure `IVOROVI_SCANNING_CLAMD_HOST` |
| Throughput | About 36 fully specified rows per second per job on the reference machine (KI-52); two jobs run in parallel per instance (`IVOROVI_IMPORT_CONCURRENCY`) |

Source files and raw row values are deleted `IVOROVI_IMPORT_RETENTION_DAYS` (default 30) days after an import finishes
or is abandoned; outcomes, counts and audit records stay. Configuration: `runbooks/configuration.md`.

## Sample files

`docs/samples/student-import/` (synthetic, for the Meridian demonstration organization; regenerate with
`python3 scripts/demo/import_samples.py`):

| File | Content |
|---|---|
| `meridian-mie-intake.csv` / `.xlsx` | 16 valid students for Meridian Institute of Engineering (generated IDs, registration numbers, admission, batch, guardian) |
| `meridian-mie-with-issues.csv` | 8 rows: one valid row and one example of each common problem (invalid email, unknown batch, missing first name, implausible date of birth, transfer without previous institution, possible duplicate, exact duplicate) |
| `meridian-mcc-intake.xlsx` | 6 students for Meridian College of Commerce (entered IDs `MCC-9999`) |

The demonstration seed (`scripts/demo-data.sh rebuild`) imports the MIE intake (all or nothing) and leaves the file with
issues validated and awaiting decisions, so every import screen can be shown.
