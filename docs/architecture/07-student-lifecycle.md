# Student lifecycle (increment 1.5)

Module: `com.ivorovi.students` (Spring Modulith module "Students"). Migration: `V202611041000__students_lifecycle.sql`.
All tables are tenant-owned with the standard RLS policy and composite foreign keys.

```
Student profile ── 0..1 open admission record ── confirms → ADMITTED
      │                                              (cancelled admissions are kept)
      ├── enrolments: INITIAL → PROGRESSION / PROMOTION / BATCH_TRANSFER / COURSE_TRANSFER   (one current row; history kept)
      ├── guardians (encrypted contact details, one primary)
      ├── documents (quarantine → scan → CLEAN | INFECTED | ERROR; verification; removal + purge)
      ├── status history (from, to, effective date, reason, actor, time) + PII-free event log
      └── 0..1 sign-in account (on demand; STUDENT role only)
```

## Records

| Table | Purpose and rules |
|---|---|
| `student` | Profile, independent of sign-in accounts. Home institution (university/college), **scope unit** (department of the current enrolment, else of the admission, else the institution), student ID (unique per organization, generated or validated by the institution's rule), registration number (unique, optional format mask), roll number, previous institution ID, names, gender and nationality (optional), encrypted date of birth / personal email / mobile / alternate phone / addresses, institutional email, lifecycle status with effective date, profile active flag, linked user account |
| `student_number_rule`, `student_number_counter` | Per university/college: GENERATED template (`{INST}`, `{YYYY}`, `{YY}`, one `{SEQ:n}`) with an atomic per-year counter, or MANUAL with a format mask; colleges without a rule inherit their university's; no rule = manual entry with a safe default format. Masks never accept raw regular expressions |
| `admission_record` | Admission number (unique per institution), date, type (regular, lateral entry, transfer, organization-defined), category (personal information), entry academic year, program and course of the student's institution, department (from the course), entry term (regular = 1), previous institution (required for transfers), status PROVISIONAL / CONFIRMED / CANCELLED. One open admission per student. Not the admissions application/screening workflow (later module) |
| `enrolment` | Organization → institution → campus → department → program → course → batch → academic year → calendar term (optional) → course term, enrolment date, status, change type. One current row per student (partial unique index); every change closes the current row (`COMPLETED` or `TRANSFERRED`, end date) and inserts a new one linked to it |
| `guardian` | Name, relationship, encrypted mobile / email / address, primary and emergency flags (one primary per student); at least a mobile number or an email |
| `student_document` | Category (built-in or organization-defined), title, sanitised file name, content type from magic bytes (PDF/JPEG/PNG), size ≤ 5 MB, SHA-256, object path, scan status and detail, verification status and note, removal with reason; files purged after the retention period |
| `student_status_history`, `student_event` | Lifecycle log and change log; the application role cannot update them; event summaries never contain personal values |
| `student_option` | Organization-defined admission types, admission categories and document categories (deactivated, never deleted) |

## Lifecycle

| From | To | How |
|---|---|---|
| PROSPECTIVE | ADMITTED | an admission is confirmed |
| PROSPECTIVE, ADMITTED | CANCELLED | status change (open admission cancelled); or cancelling the admission of an admitted student |
| ADMITTED | ENROLLED | initial enrolment (batch of the admitted course, capacity checked) |
| ADMITTED, ENROLLED, ACTIVE, SUSPENDED | WITHDRAWN | status change; the current enrolment is closed as WITHDRAWN |
| ENROLLED | ACTIVE | status change |
| ENROLLED, ACTIVE | SUSPENDED | status change; the current enrolment is marked SUSPENDED (kept current) |
| SUSPENDED | ACTIVE | reinstatement; the enrolment becomes ACTIVE again |
| ACTIVE | GRADUATED | status change; the current enrolment is closed as GRADUATED |
| GRADUATED | ALUMNI | status change |
| WITHDRAWN, CANCELLED, ALUMNI | — | final |

Every transition is validated on the server, needs a reason, has an effective date that cannot precede the current
status's date, and is recorded (previous and new status, effective date, reason, actor, time). Simultaneous changes are
serialised with a row lock and the optimistic version. Students are never physically deleted once they have an
admission, an enrolment, a document or an account; a profile created in error (prospective or cancelled, without any
of these) can be deleted, and the hash-chained audit trail keeps the record of that deletion.

## Enrolment changes

| Change | Validation |
|---|---|
| Initial | Student ADMITTED with a confirmed admission; batch of the admitted course; term defaults to the admission's entry term |
| Progression | Same batch; a later course term |
| Promotion | Same course; a later academic year (starting after the current one); a later term |
| Batch transfer | Same course, another batch; reason required |
| Course transfer | Another course of the student's institution; reason required; `student.manage` in **both** departments |
| All | Course and program active; batch ACTIVE or PLANNED; academic year applies to the program and is not completed; calendar term belongs to the year and is not completed; term within the course length; batch capacity (seat counting serialised with a lock on the batch); effective date not before the current enrolment started |

## Access

| Permission | Allows |
|---|---|
| `student.read` | Profiles, admissions (without category), enrolments, history, dashboard — for students whose scope unit lies in the caller's units (down the tree only) |
| `student.personal.read` | Date of birth, contact details, addresses, gender, nationality, admission category, guardians (every guardian view is audited) |
| `student.manage` | Create and edit profiles, admissions, enrolments, guardians (with personal read), lifecycle status, numbering rules of an institution |
| `student.document.read` / `.manage` | List and download clean documents / upload, verify, reject, remove, request a rescan |
| `student.account.manage` | Create a sign-in account on demand (separately authorized; not held by admission officers) |

Grants (migration): organization and institution administrators all six; admission officers read, personal, manage,
documents (not accounts); department heads read, personal, document read; campus administrators, faculty, examination
controllers and management read only. The STUDENT role holds none.

## Sign-in accounts

Created only through `POST /students/{id}/account` by a holder of `student.account.manage`, for admitted, enrolled,
active, suspended or graduated students. The identity module's published `StudentAccounts` port creates an INVITED
account, assigns the system STUDENT role at the student's institution and sends the invitation after commit (existing
invitation and Keycloak flow). Accounts linked to a student can never receive another role
(`student_account_role_restricted`). Account creation, role assignment and the invitation are audited.

### Student accounts and lifecycle (KI-46, defined at the 1.5 acceptance; not yet automated)

| Lifecycle change | Required account behaviour | Rationale |
|---|---|---|
| ACTIVE/ENROLLED → SUSPENDED | Account **suspended** (sign-in refused, role assignment kept) | Suspension is temporary; reinstatement restores access without a new invitation |
| SUSPENDED → ACTIVE (reinstated) | Account **reactivated** | Mirrors the suspension |
| → WITHDRAWN or → CANCELLED | Account **deactivated** and the STUDENT role assignment ended on the effective date | The person is no longer a student; records stay, access ends |
| ACTIVE → GRADUATED → ALUMNI | Account **kept active** with the STUDENT role until an alumni policy (later module) defines alumni access; results and certificates remain accessible | Graduates commonly need transcripts and certificates |
| Account deactivated by an administrator | Lifecycle unchanged | Sign-in access and academic status are separate concerns |

Implementation (planned with the student self-service module): the lifecycle transition publishes
`student.status_changed`; the identity module applies the table above through its published port, in the same
transaction, with an audit record per account change. Until then, administrators align accounts manually in People, and
the profile's account panel shows the account status next to the lifecycle status.

## Dashboard

`GET /students/overview`: total (excluding cancelled), active (enrolled or active), prospective, newly admitted
(confirmed admissions dated in the last 30 days), suspended, withdrawn, graduated and alumni, documents awaiting scan,
and current enrolments by program, course, batch and academic year (top 10). Computed from the caller's visible students
only and cached per organization, read scope and student generation (every change bumps it after commit).
