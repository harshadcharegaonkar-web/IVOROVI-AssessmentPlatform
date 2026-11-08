# Runbook — question bank (Increment 2.1)

## Images

- Accepted: PNG and JPEG, at most 5 MB and 4096 × 4096 each, at most 15 MB of images per question.
- Every upload is stored in quarantine (`t/{org}/quarantine/question-assets/{id}`) and scanned.
  - Only CLEAN images are re-encoded (metadata removed) and stored for serving (`t/{org}/question-assets/{bank}/{id}`).
  - INFECTED and ERROR images are never served and block submission of any question that uses them.
- **Release blocker (unchanged):** a production malware scanner (ClamAV or equivalent, ADR-0006). Without a scanner,
  uploads fail closed (503 `scanning_unavailable`) outside development and test. `NOT_SCANNED_DEV` exists only in
  development and test.
- Scanner outage: uploads made during the outage are recorded as ERROR (fail closed). Ask authors to upload again
  after the scanner recovers; nothing is re-scanned automatically.
- Quotas: `PUT /api/v1/question-asset-quotas?orgUnitId=…` (`question.bank.manage`). The default is
  `IVOROVI_QUESTION_ASSET_QUOTA_BYTES` (2 GB). The closest quota at or above a bank's unit applies.
- Cleanup: a daily leased job (`ivorovi.questions.asset-cleanup-cron`, 03:41) removes images older than 30 days that
  no question version links. It runs with `IVOROVI_QUESTION_ASSET_CLEANUP_ENABLED=true`.
  - The database refuses to delete a linked image.
  - Each removal is audited as `question.asset.purged`.

## Import

- CSV or XLSX, at most 10 MB and 2,000 rows. Use the template (`GET /api/v1/question-imports/template`).
- Files are scanned before parsing. An infected file returns 400; a scanner failure returns 503. Both are audited.
- Answer keys of import rows are stored encrypted until commit and deleted at commit. Rows returned to users never
  contain keys.
- Exact duplicates are detected by hash; near duplicates by trigram similarity ≥ 0.85 (`question_near_duplicate`).

## Governance incidents

- Bank and floor policies can be made stricter at once. Weakening waits for a second administrator (Approvals).
- A question approved in error is retired (`question.approve`, reason required). Versions are never edited or deleted.
- Answer-key access review: audit actions `question.answer_key.viewed` and `question.answer_key.view_denied`; refusals
  are also logged as security events.
- Privileged roles (Exam Controller): see `privileged-role-bootstrap.md`.
