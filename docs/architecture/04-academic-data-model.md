# Academic data model (increment 1.4)

Module: `com.ivorovi.academics` (Spring Modulith module "Academic structure"). Migration: `V202611031100__academics_structure.sql`.
Every table carries `tenant_id` with the standard `tenant_isolation` RLS policy and composite foreign keys
`(tenant_id, …)`, so a row can only ever reference rows of the same organization.

```
University / College ──owns──▶ Program ──has──▶ Course (branch) ──run by──▶ Department
                                                    │
Any unit ──owns──▶ Subject (master) ◀──curriculum_entry (term n)──┘
                     ▲  └── subject_prerequisite (no cycles)
Organization / University / College ──owns──▶ Academic year ──has──▶ Academic term (1..12)
Course + Academic year ──▶ Batch (section, capacity)
Subject + Batch + Term (+ course term n) ──▶ Subject offering ──▶ Faculty assignment ──▶ Staff profile (1.2)
```

| Table | Key columns | Integrity rules |
|---|---|---|
| `program` | owner (university/college), code, name, qualification level, awarded qualification, term system (SEMESTER/TRIMESTER/QUARTER/ANNUAL/OTHER), terms per year, duration (years, 1 decimal), total terms, status | code unique per owner (case-insensitive); total terms default ⌈duration × terms per year⌉; cannot shrink below terms used by courses/curricula; owner immutable |
| `course` | program, department, code, name, specialization, optional total terms (≤ program), status | department must lie inside the program's institution; code unique per program; program immutable |
| `subject` | owner (any unit), code, name, description, credits (0–40), delivery (theory/practical/both), elective default, category, status | code unique per owner; one master reused by every course — never copied |
| `curriculum_entry` | course, subject, term number, elective | unique (course, subject, term); term ≤ course terms; subject owner must be the course's institution, inside it, or an ancestor (e.g. the university); removable only while never offered |
| `subject_prerequisite` | subject, prerequisite | no self-reference (DB check), no cycles (recursive check in the service) |
| `academic_year` | owner (null = organization, or university/college), code, name, start/end, status PLANNED/ACTIVE/COMPLETED | end > start (DB); same-owner years cannot overlap unless *System Settings → allow overlapping academic years*; serialised by an advisory lock per owner |
| `academic_term` | year, sequence 1–12, name, start/end, registration window, examination window, status | inside the year; never overlapping another term of the year; window pairs both-or-neither (DB); registration closes by the term end; exams inside the term; active only in an active year |
| `batch` | course, academic year, code, name, admission year, expected graduation year (≥ admission), section, capacity, status PLANNED/ACTIVE/GRADUATED/INACTIVE | year must apply to the program (organization-wide or owned by its institution or an ancestor) and not be completed; code unique per course |
| `subject_offering` | subject, course, batch, year, term, course term number, section, credits, status PLANNED/ACTIVE/COMPLETED/CANCELLED | the curriculum must teach the subject in that course term; no duplicate (batch, term, subject, section) among non-cancelled offerings (partial unique index) |
| `faculty_assignment` | offering, staff profile, primary, starts/ends on, ended at/by, end reason | one open assignment per staff per offering; at most one open primary (partial unique indexes); only active teaching staff (FACULTY, ACADEMIC_ADMIN, DEPARTMENT_ADMIN) with a current membership in the institution; ending keeps the row as history |

## Scope and permissions

* Manage permissions are evaluated at the owning unit and inherited from ancestors: programs at the owner, courses at the
  department, subjects at the owner, calendars at the owner (organization-wide years need the tenant-wide grant),
  batches/offerings (`batch.manage`) and faculty (`faculty.assign`) at the course department.
* `academics.read` is visible **up and down** the tree from the reader's scope (a department head sees the program and the
  organization calendar their course uses, but not sibling departments' courses); organization-wide years are visible to
  every reader. Lists filter in SQL with ltree (`path <@ roots OR path @> roots`).
* Organization units that own academic records cannot be deleted (dependents include `academicRecords`) or moved.

## Concurrency, audit, caching

* Every update locks its row (`SELECT … FOR UPDATE`) and checks the optimistic `version`; faculty changes lock the offering.
* Every change writes an audit event (`academics.*`) and an outbox event, and bumps `ivorovi:academics-gen:t:{tenant}` after
  commit; dashboard KPIs are cached per organization, read scope and generation (5 minutes TTL).
* Nothing is hard-deleted except unused curriculum and prerequisite links (audited); deactivation keeps history.

## Configurable terminology

`Terminology` (tenancy module) adds PROGRAM, COURSE, SUBJECT, TERM, BATCH labels to the existing unit-type labels in
`tenant_settings.settings.labels`; internal types and API values never change.
