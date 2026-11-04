# 15 — Database design and migration strategy

## Principles

* Same PostgreSQL database and schema, Flyway migrations, RLS on every tenant table (`tenant_id = current_tenant_id()`),
  composite `(tenant_id, id)` foreign keys, field encryption for personal and secret data (`FieldCipher`).
* **Phase 1 tables are changed only additively** (new nullable columns, new tables, new indexes). No renames, no type
  changes, no dropped columns on Phase 1 tables during Phase 2 — protecting completed modules.
* Expand/contract for any later breaking change: add new structure → dual-write → backfill (batched job, idempotent) →
  switch reads → remove old structure in a later release.
* Every migration is tested on a copy of the benchmark data set (36,400 students) and timed; long-running backfills run
  as application jobs, not inside Flyway.

## New tables (by module)

| Module | Tables (main) | Notes |
|---|---|---|
| candidates | `candidate`, `candidate_contact`, `candidate_document` | encrypted personal fields; dedup lookup via security-definer function (pattern from 1.6) |
| admissions | `admission_cycle`, `program_offering`, `application_form_version`, `application`, `application_stage_result`, `merit_list`, `merit_entry`, `offer` | form answers as schema-validated `jsonb` (encrypted for personal fields) |
| itembank | `item_bank`, `item`, `item_version`, `item_media`, `item_review`, `item_usage`, `taxonomy_*` | answer key column encrypted |
| assessments | `assessment`, `assessment_version`, `assessment_section`, `selection_rule` | versions immutable |
| scheduling | `exam_event`, `exam_session`, `venue`, `room`, `allocation`, `accommodation`, `admit_card` | |
| delivery | `attempt`, `paper` (sealed JSON + hash), `response_journal` (**partitioned by exam_event**), `response_latest`, `submission_receipt` | journal partition per event; detach + archive after retention |
| integrity | `integrity_profile`, `signal` (**partitioned**), `snapshot` (object refs), `flag`, `incident`, `decision`, `appeal` | evidence in object storage, encrypted |
| evaluation | `evaluation_plan`, `evaluation_task`, `score_line`, `rubric`, `moderation_action`, `ai_suggestion`, `reevaluation_request` | |
| results | `result_set`, `result_revision`, `result_entry`, `rank_entry`, `scorecard`, `result_query` | |
| placement | `company`, `drive`, `drive_eligibility`, `shortlist`, `shortlist_entry`, `consent`, `recruiter_access` | |
| ai | `ai_provider`, `ai_use_case_policy`, `prompt_template_version`, `ai_invocation` (**partitioned by month**) | |
| coding | `language_profile`, `test_case` (hidden tests encrypted), `code_run`, `code_verdict` | runner has no DB access; results via queue |
| licensing | `plan`, `entitlement`, `usage_meter` | |
| KI-59 | `service_identity`, additive columns on `api_client`, `role_assignment` | see `architecture/10` |

## Volume estimates (per 10,000-candidate admission test)

| Table | Rows | Note |
|---|---|---|
| attempt / paper | 10k / 10k | paper ≈ 5–20 kB JSON |
| response_journal | ≈ 1.5–3 M | 100 items × several changes; partitioned, append-only |
| signal | ≈ 2–5 M | L1/L2 levels; partitioned, short retention |
| score_line | ≈ 1 M | |
| snapshot objects | ≈ 200–600 k | object storage, not DB |

## Performance patterns

Hot attempt state in Valkey with write-through to PostgreSQL; journal inserts batched per request; `response_latest`
upsert keyed by `(attempt, item)`; partition pruning by event; BRIN indexes on time columns for signals; read replicas
for analytics and reports (deployment option); outbox throughput fix (KI-64) before delivery goes live.

## Retention and archival

Retention jobs per data class (see `18-business-readiness.md`): detach old journal/signal partitions, export to
encrypted archive in object storage for the statutory period, then delete; evidence of deletion recorded in the audit
trail.
