# Student data: security and privacy controls (increment 1.5)

These are **technical controls**. They support, but do not by themselves establish, compliance with the India Digital
Personal Data Protection Act, 2023 (DPDP) or any other law. Legal review of notices, consent, purposes, retention periods
and data-principal rights is outstanding (KI-48). No compliance claim is made.

## Data minimisation and classification

| Class | Fields | Protection |
|---|---|---|
| Identifiers | student ID, registration and roll number, names, institutional email | Tenant RLS; scope-based read (`student.read`); searchable |
| Personal | date of birth, gender, nationality, personal email, mobile, alternate phone, current and permanent address, admission category | `student.personal.read` required; omitted from responses otherwise; contact details, date of birth and addresses **encrypted** (AES-256-GCM, key from `IVOROVI_DATA_KEY`, column name and organization bound as associated data) |
| Guardian | name, relationship, mobile, email, address | `student.personal.read` to view (every view audited), `student.manage` to change; contact details encrypted |
| Documents | identity proofs, certificates, photographs | `student.document.read/manage`; quarantined until scanned clean; downloads streamed through the API (no public links), `Content-Disposition: attachment`, `nosniff`, `no-store`; every download and refused download audited |

Gender and nationality are optional and recorded only where an institution requires them. Admission categories are
treated as personal information because they may reveal protected characteristics.

## Controls

* **Isolation:** every student table has PostgreSQL RLS on `tenant_id`; composite foreign keys keep references inside the
  organization; cross-organization reads return 404 (tested).
* **Least privilege:** six separate permissions; faculty see profiles without personal details; account creation is a
  separate permission that admission officers do not hold; student-linked accounts can only hold the STUDENT role.
* **Audit:** create, update (field names only), status changes, admissions, enrolments, guardians (including views),
  documents (upload, scan verdict, download, refusal, verification, removal, purge) and accounts are written to the
  hash-chained audit trail. Audit details and the student event log never contain personal values (tested by scanning
  the trail for the test's personal data).
* **Logging:** services log identifiers and exception class names only; scanner errors are logged without content.
* **Malware (ADR-0006):** extension and MIME checks are not malware protection. Content type comes from magic bytes;
  uploads land in `quarantine/…` with state PENDING and are promoted to `documents/…` only after a CLEAN verdict.
  PENDING, INFECTED and ERROR documents cannot be downloaded, verified or processed. In STAGING/PRODUCTION uploads are
  refused (503) unless a scanner is configured (fail closed). Deploying ClamAV and passing the release test remains a
  **release blocker** before real student documents are accepted (KI-23).
* **Retention and deletion:** removed and infected document files are purged from object storage after
  `IVOROVI_DOCUMENT_RETENTION_DAYS` (default 30; the record and its audit history stay). Students with admissions,
  enrolments, documents or accounts are never physically deleted; deactivation hides a profile from changes. A data-
  principal erasure workflow that respects statutory academic-record retention is not yet built (KI-48).
* **Keys:** the field-encryption key comes from the deployment's secret store; the API refuses to start without it.
  Rotation is manual for now (KI-49).
* **Demonstration and test data:** synthetic only (`scripts/demo-data.sh`, test fixtures); no real student data is used
  in development or tests.
