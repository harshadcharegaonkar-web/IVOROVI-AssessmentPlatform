# Session 5: admission (E), learning analytics (F), placement (G). Day 1 proposal, 2026-11-16

Status: **proposal for Session A's approval**. Nothing is implemented until A approves. Verified against `main` at `869153c`.

## 1. Reuse report

### What exists and will be reused (no second implementation)

| Need | Existing piece (verified) | Used by |
|---|---|---|
| Programmes, courses, batches, academic years, terms, subjects | `academics` (`program`, `course`, `batch`, `academic_year`, `subject`, `subject_offering`, `curriculum_entry`); routes `academics/*` | E (programmes on offer, seats per course/batch), F (subject, cohort = batch/course/year), G (eligibility by programme/batch) |
| Student profile, admission record, enrolment, lifecycle, student numbering | `students` `StudentService.create`, `AdmissionService.create/setStatus(CONFIRMED)`, `EnrolmentService` initial enrolment with capacity lock, `StudentNumbering` | E: conversion of an accepted offer into PROSPECTIVE student → admission record → CONFIRMED (ADMITTED) → initial enrolment. No parallel student table |
| Document quarantine → ClamAV → CLEAN/INFECTED/ERROR, fail closed in STAGING/PRODUCTION, EICAR tests | `platform.scanning.MalwareScanner`, `ClamAvScanner`, `platform.storage.ObjectStorage`, `TenantObjectKeys`; the pipeline itself is in `students.internal.DocumentService` (upload, `scanPending`, promote, download only when CLEAN, `uploadsAllowed`) | E: applicant documents. Pipeline must be made reusable (§2.3 S-1) |
| Bulk import (CSV/XLSX, mapping, validation, duplicates) | `platform.tabular.*` readers/limits; `students.internal.StudentImportEngine` | E: import of applicants from an external form (optional, reduced depth); G: import of employer contacts (optional). Only the `platform.tabular` layer, no copy of the student engine |
| Candidate identity, separate realm, MFA, verified email, self-registration on a portal | `exams` candidate realm, `CandidateService`, exam portals with `selfRegistration`; exam app | E: applicants sign up and sign in as candidates of an **admissions portal**; G: placement candidates sign in as candidates (students already have emails) |
| Schedules, enrolment, invitations, session codes, delivery, scoring, results | `scheduling.ScheduleService` (create, enrol, invite), `exams` (`ExamSessions`, `ExamEligibility`, attempts, `ScoringService`, `ResultService` with R-1 four-eyes release) | E entrance test, G aptitude and technical tests (and coding via add-on D once accepted). **One examination engine**; admissions/placement only *create* schedules over published assessments and *read released* results |
| Assessments | `assessments.PublishedAssessments` (published versions) | E, G: choose the published assessment for a test |
| Learning outcomes and question tagging | **Already exist**: `learning_outcome` (per `question_subject`, Bloom level) and `question_version.outcome_ids`, `topic_id`, `difficulty_id`; `question_subject.academic_subject_id` links to `academics.subject` | F: no new tagging model; attainment = per-question scores (`exam_answer_score`) joined to the outcome tags of the question version |
| Per-question scores, released results | `exam_score`, `exam_answer_score` (immutable, outcome CORRECT/PARTIAL/...), `exam_result_review` | F attainment; E merit; G ranking: only **released** results |
| Four-eyes | `approvals.ApprovalService` + `ApprovalHandler` (requester ≠ approver, payload hash `unchanged`, live `mayDecide`) | E merit-list publication and offer release; G shortlist release to an employer |
| Notifications | `notifications.NotificationService`, outbox, e-mail channel | E (application received, shortlisted, offer), G (drive invitation, shortlist) |
| Audit | `platform.audit.AuditLog` (hash chained) | All; ids and counts only, no names, e-mails, marks or categories in details |
| Encryption | `platform.crypto.FieldCipher`, `EncryptedColumns`, keyed e-mail index as in `exam_enrolment`/`candidate_account` | Applicant DOB, contact, address, category; placement candidate contact; employer contact persons |
| Reports, dashboards | `insights` (`ReportService` catalogue with personal-column marking, `DashboardService`, charts in `routes/insights/Charts.tsx`) | F reports register in the insights catalogue pattern; E/G funnel counts |
| Feature flags, permissions | `module.admissions`, `module.placement` flags; `admission.read/manage`, `placement.read/manage` (PLANNED) in `Permission` | Switch these to AVAILABLE; add a few (§2.4) |

### What must be new

- **admissions**: cycles, programme offerings with seats and eligibility rules, application form and applications, applicant documents (via the shared pipeline), screening, entrance-test link, merit list, offers, acceptance, conversion.
- **analytics**: outcome attainment computation and snapshots, student/subject/cohort analysis, at-risk rules and list. No new tagging.
- **placement**: employers, drives, eligibility, candidate profiles derived from students, candidate consent, test link, ranking, shortlist, employer view.
- Candidate-side screens: application portal (E), placement consent and drive view (G).

### Gaps found in the existing code (they shape the contract)

1. **No public API in `students`, `academics` or `scheduling`**: only `internal` services. Every cross-module call I need requires a small public API in those modules (owned by Session 2 / A).
2. **Exam candidates are not linked to students or applicants**: `exam_enrolment` and `candidate_account` hold only an encrypted e-mail. F (attainment per student) and G (results of a student) cannot attribute results without a subject reference.
3. **The quarantine pipeline is private to `students`.**
4. **Results are read only by the exams screens**; there is no module API for released per-question results.
5. **Migration ordering**: `main` already holds `V202611170010` … `V202611190010`. A Day-1 Session 5 migration `V202611164001` sorts *below* migrations that are already applied on any persistent database (demo environment); Flyway's default `outOfOrder=false` will refuse it. On a fresh CI database it is fine. Needs a lead decision (§2.5).

## 2. Contract proposal

### 2.1 New modules (Spring Modulith, own top-level package, `internal` + `web`)

| Module | Depends on (public APIs only) | Publishes |
|---|---|---|
| `admissions` | `academics.AcademicCatalogue` (S-3), `students.StudentAdmissions` (S-2), `scheduling.ExamScheduling` (S-4), `exams.ExamResults` (S-5), `platform.documents.QuarantinedDocuments` (S-1), `approvals`, `notifications`, `assessments.PublishedAssessments` | `admissions.AdmissionOutcomes` (read-only: admitted applicants per cycle, for insights/demo data) |
| `analytics` | `exams.ExamResults` (S-5), `questions.OutcomeCatalogue` (S-6), `academics.AcademicCatalogue` (S-3), `students.StudentDirectory` (S-2, read) | `analytics.Attainment` (read: per student/subject; used by placement eligibility "min attainment" and by insights) |
| `placement` | `students.StudentDirectory` (S-2), `academics.AcademicCatalogue`, `scheduling.ExamScheduling`, `exams.ExamResults`, `approvals`, `notifications`, `analytics.Attainment` (optional) | none |

`ModularityTest` stays green; no `internal` type crosses a module.

### 2.2 Key design: how entrance and placement tests reuse the examination engine

- Admissions/placement never create attempts, papers or scores. They call `ExamScheduling.createSchedule(versionId, portalId, orgUnitId, title, window, duration)` (the existing `ScheduleService.create` under the caller's `exam.schedule.manage`) and `ExamScheduling.enrol(scheduleId, List<Enrollee(email, displayName, subjectType, subjectRef)>)` (existing enrolment + `invite`).
- New on `exam_enrolment`: `subject_type` (`STUDENT` | `APPLICANT` | `PLACEMENT_CANDIDATE`, nullable) and `subject_ref uuid` (nullable). Existing uploads leave them null. Set only through the module API, never from CSV. Not personal data (an opaque id).
- Delivery, invigilation, scoring, re-evaluation (A's G) and R-1 four-eyes release are untouched. Admissions and placement read **only released** results through `ExamResults.released(scheduleId)` → `(subjectType, subjectRef, attemptId, awarded, max, status)` and `ExamResults.releasedItems(attemptIds)` → `(attemptId, position, questionVersionId, outcome, awarded, marks)`. A result that is not released does not exist for them.
- Applicants: an exam portal per institution flagged for admissions with `selfRegistration=true`; an applicant's candidate account (separate realm, verified e-mail, MFA per portal policy) is the identity for both the application and the entrance test.

### 2.3 Changes needed in modules I do not own (each a small, separate, flagged commit for A's review)

| # | Module (owner) | Change |
|---|---|---|
| S-1 | `platform` (Session 2) + `students` | Extract the quarantine → scan → promote → CLEAN-only download pipeline from `students.internal.DocumentService` into `platform.documents.QuarantinedDocuments` (owner type + id, category, PDF/JPEG/PNG, 5 MB, magic bytes, SHA-256, scan state, fail closed, `UploadRefusals`, audit). `DocumentService` delegates; behaviour, table and tests of student documents unchanged; existing tests must stay green. Admissions gets its own table (`admission_document`) through a table-name/owner adapter, or a generic `quarantined_document` table: **A to choose**; I recommend own table + shared code (no data migration of student documents). |
| S-2 | `students` (Session 2) | Public `students.StudentAdmissions.convert(ConversionCommand)` → creates the PROSPECTIVE student, admission record (CONFIRMED) and, if a batch is given, the initial enrolment, in one transaction, under the caller's `student.manage`; idempotent on the application id. Public read `students.StudentDirectory` (summary by ids/batch/course for `student.read` scope: id, student no, name, batch, status; institutional e-mail only). |
| S-3 | `academics` (Session 2) | Public read `academics.AcademicCatalogue`: program/course/batch/year/subject lookups (ids, codes, names, status, capacity). |
| S-4 | `scheduling` (A) | Public `scheduling.ExamScheduling` (create schedule, enrol with subject ref, invite, cancel) over the existing `ScheduleService`; migration adding `subject_type`, `subject_ref` to `exam_enrolment` (in A's range or mine: A to decide). |
| S-5 | `exams` (A) | Public `exams.ExamResults` (released only, §2.2), authorized by the caller's `exam.results.read` **or** a module-to-module grant scoped to the schedule's institution plus the subject type the caller owns. |
| S-6 | `questions` (A) | Public read `questions.OutcomeCatalogue`: learning outcomes and `outcome_ids` per question version (never content or keys, ADR-0009). |
| S-7 | `platform.security.Permission` | Set `admission.read/manage`, `placement.read/manage` AVAILABLE; add `admission.decide` (privileged: merit/offer approval), `analytics.read`, `analytics.manage` (outcomes thresholds, at-risk rules), `placement.decide` (privileged: shortlist release). Role grants in my migrations. |

### 2.4 Data model (all tables: `tenant_id`, composite FKs, standard RLS policy, app-role grants; history tables append-only with the existing guard pattern)

**admissions** (`V20261117` + `4nnn`, see §2.5)
- `admission_cycle` (institution org unit, academic year, name, opens/closes, status DRAFT/OPEN/CLOSED/COMPLETED)
- `admission_offering` (cycle, program, course, batch optional, seats, category quotas jsonb, entrance schedule id optional, weights: qualifying marks % / test %)
- `admission_eligibility_rule` (offering, kind: MIN_QUALIFYING_PERCENT, REQUIRED_SUBJECT, AGE_RANGE, REQUIRED_DOCUMENT; parameters jsonb; no free regex)
- `applicant` (candidate account id, e-mail index, names, `dob_enc`, `mobile_enc`, `address_enc`, `category_enc`, `gender`, `nationality` per student patterns)
- `admission_application` (cycle, offering, applicant, application no, qualifying marks, status DRAFT → SUBMITTED → ELIGIBLE/INELIGIBLE → SHORTLISTED → TESTED → RANKED → OFFERED → ACCEPTED/DECLINED/EXPIRED → CONVERTED, or WITHDRAWN/REJECTED; version)
- `admission_application_event` (append-only; from/to, reason code, actor, time; no personal values)
- `admission_document` (via S-1; required categories per offering; acceptance requires CLEAN + VERIFIED)
- `admission_screening` (application, rule id, result PASS/FAIL/MANUAL, evaluated at)
- `merit_list` (offering, version, status DRAFT/PENDING_APPROVAL/PUBLISHED/SUPERSEDED, approval id, payload hash) and `merit_list_entry` (immutable once published: rank, composite score, category, application)
- `admission_offer` (application, merit list, status ISSUED/ACCEPTED/DECLINED/EXPIRED/WITHDRAWN, expires at, approval id, student id after conversion)

**analytics**
- `outcome_target` (learning outcome or subject, threshold %, attainment levels 1–3)
- `attainment_snapshot` (schedule, student, outcome, awarded, max, computed at; recomputed only from released results; one row per key, replaced atomically per schedule)
- `at_risk_rule` (scope: course/batch; kind: ATTAINMENT_BELOW, FAILED_ASSESSMENTS, DECLINING_TREND; parameters) and `at_risk_flag` (student, rule, reasons codes, status OPEN/ACKNOWLEDGED/RESOLVED, note by staff; append-only history). A flag is advice for staff; no automated consequence.

**placement**
- `employer` (name, sector, contact person `name_enc`/`email_enc`/`phone_enc`, status)
- `placement_drive` (employer, institution, title, role, package text, opens/closes, status DRAFT/OPEN/TESTING/SHORTLISTING/CLOSED; aptitude and technical schedule ids)
- `drive_eligibility_rule` (program/course/batch lists, min attainment %, no open backlog, graduation year)
- `placement_profile` (student id, headline, skills tags, CV document via S-1 optional; derived fields read from students, not copied)
- `placement_consent` (student, drive or employer, purpose, text version, granted/withdrawn at; append-only)
- `drive_registration` (drive, profile, status ELIGIBLE/REGISTERED/TESTED/RANKED/SHORTLISTED/REJECTED/OFFERED/HIRED)
- `drive_shortlist` (drive, version, status, approval id, hash) and `drive_shortlist_entry` (rank, score)
- `employer_access` (employer user account or share token record, drive, expires) and the employer view only lists registrations with a **current consent**; every employer read audited (ids only).

### 2.5 Migrations

Range `V<date>4nnn`. Because `main` already contains migrations dated 2026-11-17 to 2026-11-19, a migration written today (`V202611164001`) would sort below applied ones and fail Flyway validation on any persistent database. **Proposal:** I date my files from the first day *after* the highest version on `main` at the time of merge, or A enables `outOfOrder` for the demo only. I recommend the first (no config change). Planned: `…4001` admissions core, `…4002` admission documents, `…4003` merit/offers, `…4010` analytics, `…4020` placement, `…4030` permissions and grants. A owns the `exam_enrolment` column change unless delegated.

### 2.6 REST endpoints (to be added to `docs/api/openapi.json` once approved; staff under `/api/v1`, candidate under the exam portal prefix)

Staff, admissions (`admission.read` / `admission.manage` / `admission.decide`):
`GET|POST /admissions/cycles`, `GET|PATCH /admissions/cycles/{id}`, `POST /admissions/cycles/{id}/status`;
`GET|POST /admissions/cycles/{id}/offerings`, `PATCH /admissions/offerings/{id}`, `PUT /admissions/offerings/{id}/rules`;
`GET /admissions/applications?cycle&offering&status&q`, `GET /admissions/applications/{id}`, `POST /admissions/applications/{id}/screen`, `POST /admissions/offerings/{id}/screen` (bulk), `POST /admissions/applications/{id}/decision` (shortlist/reject manual, reason);
`GET /admissions/applications/{id}/documents`, `GET …/documents/{docId}/content` (CLEAN only), `POST …/documents/{docId}/verify`, `POST …/rescan`;
`POST /admissions/offerings/{id}/entrance-test` (link a published assessment → creates schedule and enrols shortlisted applicants), `POST /admissions/offerings/{id}/import-results` (reads released results);
`POST /admissions/offerings/{id}/merit-lists` (draft), `GET /admissions/merit-lists/{id}`, `POST /admissions/merit-lists/{id}/submit` (four-eyes request, `admission.decide`), `POST /admissions/merit-lists/{id}/offers` (generate offers, four-eyes);
`GET /admissions/offers?…`, `POST /admissions/offers/{id}/withdraw`, `POST /admissions/offers/{id}/convert` (→ student via S-2);
`GET /admissions/overview` (funnel counts).

Candidate (applicant), admissions portal: `GET /portal/{key}/admissions/cycles` (open offerings), `GET|PUT /portal/{key}/admissions/applications/{id}` (draft), `POST …/submit`, `POST …/documents` (multipart → quarantine), `GET …/documents`, `POST /portal/{key}/admissions/offers/{id}/accept|decline`.

Analytics (`analytics.read` / `analytics.manage`): `GET|PUT /analytics/outcome-targets`, `POST /analytics/recompute?scheduleId`, `GET /analytics/students/{id}`, `GET /analytics/subjects/{id}?yearId&batchId`, `GET /analytics/cohorts?courseId&batchId&yearId`, `GET|PUT /analytics/at-risk-rules`, `GET /analytics/at-risk?scope`, `POST /analytics/at-risk/{id}/status`.

Placement (`placement.read` / `placement.manage` / `placement.decide`): `GET|POST /placement/employers`, `PATCH /placement/employers/{id}`; `GET|POST /placement/drives`, `GET|PATCH /placement/drives/{id}`, `PUT /placement/drives/{id}/eligibility`, `POST /placement/drives/{id}/eligible` (compute), `POST /placement/drives/{id}/tests` (link published assessments → schedules), `POST /placement/drives/{id}/rank`, `POST /placement/drives/{id}/shortlists` (draft), `POST /placement/shortlists/{id}/submit` (four-eyes), `POST /placement/drives/{id}/employer-access`.
Student (student account): `GET /me/placement/drives`, `POST /me/placement/drives/{id}/register`, `POST|DELETE /me/placement/consents/{id}`.
Employer: `GET /employer/drives/{id}/shortlist` (consented candidates only).

**Open question for A:** employer identity. Option 1 (recommended): staff-realm user with a new system role `EMPLOYER` scoped to the drive's institution and `placement.employer.read` only. Option 2: expiring share link (weaker). Either is audited.

### 2.7 Four-eyes and consent (product rules)

- Merit-list publication and offer issue: `ApprovalHandler` types `admission.merit.publish`, `admission.offer.issue`; approver holds `admission.decide` at the institution, is not the requester; payload hash = list contents; publishing freezes the list.
- Placement shortlist release to an employer: `placement.shortlist.release` with `placement.decide`.
- Screening by rules is automatic but every INELIGIBLE can be overridden by staff with a reason (audited); no applicant is rejected solely by an automated rule without a staff-visible decision record.
- Employer sees a candidate only with a current consent row for that drive; withdrawal removes visibility immediately; no consent → not listed.

## 3. Plan, estimates, order, risks

| # | Work | Est. (agent-days) | Days |
|---|---|---|---|
| 0 | Shared APIs S-1..S-7 (my commits, A/Session 2 review), OpenAPI additions | 1.5 | 2 |
| 1 | Admissions backend: cycles, offerings, rules, applicants (FieldCipher), applications, documents, screening | 2.5 | 3–4 |
| 2 | Admissions: entrance test link, merit list + four-eyes, offers, acceptance, conversion | 2 | 5–6 |
| 3 | Admissions UI (staff + applicant portal) and E2E | 2 | 4–7 |
| 4 | Analytics backend + UI + E2E (attainment, analyses, at-risk) | 4.5 | 5–8 (overlapping) |
| 5 | Placement backend + UI + E2E | 4.5 | 7–10 |
| 6 | Security regression (tenant isolation, authorization, audit content), demo data handover to Session 3, fixes | 1.5 | 9–10 |
| | **Total** | **≈ 18.5** (assessment §3: 17.5, +1 for the shared APIs that turned out not to exist) | |

Capacity Days 3–10 at the assessment's rate: about 16–17 agent-days. **Shortfall ≈ 1.5–2.5 days, reported now.** Options for the product owner: (a) accept the listed reduced depths (admission without the entrance-test link −1, analytics without at-risk −1); (b) Session 2 implements S-1..S-3 (it owns those modules); (c) start building on Day 1–2 once contracts are approved. Nothing is reduced unless decided.

Order: S-1..S-7 first (others depend on them) → admissions → analytics (needs only S-5/S-6) in parallel with admissions UI → placement (benefits from analytics `Attainment` and add-on D for coding tests).

Risks

| Risk | Mitigation |
|---|---|
| Shared-module changes conflict with Sessions 2/A branches | Small separate commits, first, behind interfaces; rebase daily |
| ClamAV not running in the demo environment (P-1) | Fail closed already; EICAR test in CI with the clamd container; flagged to Session 3 |
| Migration ordering (§2.5) | Lead decision today |
| Released results needed for E/F/G depend on A's results work (G manual evaluation) | Use automatic scoring + existing R-1 release, which already work; descriptive types are additive |
| Applicant portal in the exam app hits the 120 kB budget or CSP | Lazy-loaded routes; or a separate small entry. **A to decide** |
| Coding tests for placement depend on add-on D | Placement technical test works with objective/descriptive assessments; coding added when D is accepted |
| DPDP notice texts (P-4) | Draft texts labelled as drafts |
| Synthetic data | Session 3's generator; I provide the admission/placement fixtures as code, no real names |
