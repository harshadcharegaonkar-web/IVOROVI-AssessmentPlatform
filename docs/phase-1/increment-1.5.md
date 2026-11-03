# Increment 1.5: student lifecycle management

* Status: **Implemented and verified; awaiting product-owner review** (authorized 2026-11-03 with the 1.4 acceptance).
  Increment 1.6 not started.
* Depends on: 1.4 (accepted; follow-ups completed — see `increment-1.4.md`).
* Design: `docs/architecture/07-student-lifecycle.md`; privacy: `docs/security/student-data-privacy.md`.

## Scope delivered

| Area | Delivered |
|---|---|
| Student profiles | Create, view, edit, search, activate/deactivate, lifecycle status, history. Personal (name parts, date of birth, gender and nationality where required), contact (personal and institutional email, mobile, alternate number, current and permanent address) and identification (student ID, registration number, roll number, previous institution ID). Profiles are independent of sign-in accounts |
| Numbering | Per university/college: generated from a template (`{INST}{YYYY}{SEQ:4}`, atomic per-year counter) or entered and checked against a format mask; colleges inherit their university's rule; no global format assumed; registration-number masks; no raw regular expressions accepted |
| Admission records | Separate entity: number (unique per institution), date, type (regular, lateral entry, transfer, organization-defined), category (personal information), entry year, program, course, institution, department, entry term, status (provisional, confirmed, cancelled), previous institution. Not the admissions workflow (later module) |
| Enrolment | Separate entity linking organization, institution, campus, department, program, course, batch, academic year, calendar term and course term with date and status. Initial enrolment, progression, promotion, batch transfer, course transfer (authorized in both departments, reason required), suspension, withdrawal, graduation. History is never overwritten (one current row; previous rows closed and linked). Batch capacity enforced (also under simultaneous requests) |
| Guardians | One or more per student; name, relationship, mobile, email, address (encrypted), primary (one) and emergency flags; restricted to `student.personal.read`; every view audited |
| Documents | Identity proof, previous academic certificates, transfer and migration certificates, photograph, organization-defined categories. Content-type detection from file content (PDF/JPEG/PNG), 5 MB limit, tenant-isolated object storage, metadata (size, SHA-256, uploader), upload/download/refusal audit, scan and verification status, removal with reason, retention purge. **Quarantine and fail-closed (ADR-0006)**: uploads stay quarantined until a scanner reports CLEAN; PENDING/INFECTED/ERROR documents are never served; STAGING/PRODUCTION refuse uploads without a scanner. Scanner port with a ClamAV (`clamd INSTREAM`) adapter |
| Lifecycle | Prospective, admitted, enrolled, active, suspended, withdrawn, graduated, alumni, cancelled; valid transitions enforced on the server; every change records previous/new status, effective date, reason, actor and time; no physical deletion of students with academic records |
| Search | Name, student ID, registration and roll number; filters for institution, campus, department, program, course, batch, academic year, term, status and profile state; server-side pagination and sorting |
| Dashboard | Total, active, newly admitted (30 days), prospective, suspended, withdrawn, graduated/alumni, documents awaiting scan; distributions by program, course, batch and academic year — all from the caller's authorized students |
| Accounts | On demand only (`student.account.manage`); STUDENT role only, at the student's institution; existing invitation and Keycloak flow; accounts linked to a student can never receive another role; creation, role assignment and invitation audited |
| Screens (12) | Student dashboard; student listing; profile overview; create/edit; admission details; academic enrolment details; academic history; guardian management; document management; status and lifecycle history; account provisioning; search and filters. Plus student settings (numbering rules, option lists). Tabs: Overview, Personal information, Academic, Guardians, Documents, History |

## Permissions

`student.read` and `student.manage` became available; new: `student.personal.read`, `student.document.read`,
`student.document.manage`, `student.account.manage` (catalogue: 40 permissions, 25 available). Grants in
`V202611041000` (see `docs/security/permission-catalogue.md`).

## APIs (`/api/v1`)

| Resource | Endpoints |
|---|---|
| Students | `GET /students` (search, filters, sort, page), `GET /students/overview`, `POST /students`, `GET/PATCH/DELETE /students/{id}`, `POST /students/{id}/status`, `POST /students/{id}/active`, `GET /students/{id}/history`, `POST /students/{id}/account` |
| Admissions | `GET/POST /students/{id}/admissions`, `PATCH /students/{id}/admissions/{admissionId}`, `POST …/{admissionId}/status` |
| Enrolments | `GET/POST /students/{id}/enrolments` (type INITIAL, PROGRESSION, PROMOTION, BATCH_TRANSFER, COURSE_TRANSFER) |
| Guardians | `GET/POST /students/{id}/guardians`, `PATCH/DELETE /students/{id}/guardians/{guardianId}` |
| Documents | `GET/POST(multipart) /students/{id}/documents`, `GET …/{documentId}/content`, `POST …/{documentId}/verification`, `POST …/{documentId}/rescan`, `POST …/{documentId}/remove` |
| Settings | `GET /student-settings/numbering-rules`, `PUT /student-settings/numbering-rules/{orgUnitId}`, `GET/POST /student-settings/options`, `POST /student-settings/options/{kind}/{code}/status` |

OpenAPI (`docs/api/openapi.json`) and the generated TypeScript client were regenerated.

## Configuration

New: `IVOROVI_DATA_KEY` (required; field encryption), `IVOROVI_SCANNING_CLAMD_HOST`/`_PORT`/`_TIMEOUT` (required in
STAGING/PRODUCTION before documents are accepted), `IVOROVI_DOCUMENT_RETENTION_DAYS` (`docs/runbooks/configuration.md`).
Local: `scripts/dev-setup.sh` adds `DATA_ENCRYPTION_KEY` to `deploy/compose/.env`.

## Results

| Check | Result |
|---|---|
| Backend (JUnit + Testcontainers) | **181 tests**, 0 failed — incl. `StudentsIT` (12: profiles and institution numbering, encrypted and restricted personal data never logged, admission → enrolment → progression → promotion → transfers with history, capacity under simultaneous enrolment, lifecycle rules and history, simultaneous status changes, scope and isolation, filters and dashboard, quarantine/scan/download, file checks and fail-closed policy, on-demand accounts with student role only, migration grants), `ClamAvScannerTest` (4), all 1.0–1.4 suites |
| Smoke test with real Keycloak tokens | **62 passed** (adds student list, dashboard, student role denied, faculty denied creation, college admin creates, faculty sees no personal details, cross-tenant 404, document upload lands in quarantine, quarantined download refused, cancellation) |
| Frontend unit tests / typecheck / lint | **35** web tests (adds `studentModel.test.ts`), 11 UI, 3 API client; clean |
| Bundle | initial **154.8 kB gzip** (≤ 180 kB); every student page lazy; largest lazy chunk 35.8 kB |
| Playwright 1440×900 / 1024×768 / 390×844 | **81 passed** (6 new; two 1.3/1.0 selectors updated because `student.read` is no longer a planned permission and "Student Dashboard" made a non-exact "Dashboard" link ambiguous); student suites: create → admit → enrol → activate with guardians, quarantined documents, lifecycle history and account; search/filters and cancellation; faculty without personal details; settings; no horizontal scrolling on every student screen and tab at tablet and mobile |
| Accessibility | axe WCAG 2.1 AA: no serious/critical violations on dashboard, list, editor, every profile tab, dialogs and settings |
| Migration | Development database upgraded to `202611041000` in 0.11 s; 639 accounts, 629 role assignments, 25 units, 27 staff profiles, 13 programs, 13 batches and 19 offerings unchanged; 10 new tables with RLS |
| Demonstration data | `scripts/demo-data.sh rebuild` creates 20 synthetic students in the Meridian demonstration organization; every dashboard figure matches its specification |

Acceptance criteria 1–24: 1–2 `ProfilesAndNumbering`; 3–7 `AdmissionAndEnrolment`; 8 personal-data test and E2E;
9–10 `Documents` (and smoke); 11 `Lifecycle`; 12–13 `Accounts`; 14–15 `ScopeAndIsolation.filtersAndDashboard…`;
16 `readersSeeOnlyTheirPart…` and smoke; 17 audit assertions throughout; 18 migration evidence; 19–24 suites, smoke,
Playwright, axe, regressions, CI.

### Defects found during verification
* Auditing guardian views and document downloads inside read-only transactions failed; both are now normal transactions.
* The append-only rule on the event and status logs blocked deleting a profile created in error; the logs are now
  non-updatable (not non-deletable), and the deletion itself is recorded in the hash-chained audit trail.
* Seat counting was not serialised across different students (two simultaneous enrolments could exceed a batch's
  capacity); the batch row is now locked while seats are counted.
* Format masks could not express a literal prefix such as `MCC-9999`; capitals and digits other than the tokens are now
  literal.

### Not delivered / deviations (see known issues)
* ClamAV deployment and the staging release test remain a **release blocker** for real student documents (KI-23).
* Withdrawal/graduation does not suspend the sign-in account automatically (KI-46); cross-institution course transfers
  are not supported (KI-47); DPDP data-principal request workflow and legal review outstanding (KI-48); manual key
  rotation (KI-49); photographs are not used as avatars (KI-50).

Evidence: `docs/design/evidence/1.5/` (screenshots at 1440×900, 1024×768, 390×844 from the Meridian demonstration
organization; synthetic data only).
