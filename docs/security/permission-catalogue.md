# Permission catalogue

Source of truth: `backend/api/src/main/java/com/ivorovi/platform/security/Permission.java` (served at `GET /api/v1/permissions`).
Codes are stable identifiers; display names may change. Administrators cannot create codes: roles accept only catalogued,
**available** permissions. **Planned** permissions are catalogued for modules not yet delivered and cannot be granted.
**Platform** permissions belong to the platform operator and never appear in organization roles.

* **Level:** READ (view), WRITE (act, e.g. assign or evaluate), MANAGE (create, change, remove).
* **Scope:** ORGANIZATION = effective only when granted for the whole organization (custom roles containing one can only be
  assigned organization-wide and cannot be limited to a unit); UNIT = organization or a unit, inherited by everything below it.


## PLATFORM

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `platform.tenant.manage` | Manage organizations (platform) | MANAGE | ORGANIZATION | PLATFORM | Create, suspend and configure tenants |

## Organization management

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `tenant.settings.manage` | Change organization settings | MANAGE | ORGANIZATION | AVAILABLE | Change organization settings such as terminology and time zone |
| `feature.read` | View feature settings | READ | UNIT | AVAILABLE | View which modules and AI capabilities are switched on |
| `feature.manage` | Change feature settings | MANAGE | UNIT | AVAILABLE | Switch modules and AI capabilities on or off within their limits |

## Institutions, campuses and departments

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `org_unit.read` | View the organization hierarchy | READ | UNIT | AVAILABLE | View the organization hierarchy (universities, colleges, campuses, departments) |
| `org_unit.manage` | Manage universities, colleges, campuses and departments | MANAGE | UNIT | AVAILABLE | Create, edit, move, deactivate and delete organization units |

## User management

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `user.read` | View users | READ | UNIT | AVAILABLE | View user accounts, their assignments and access |
| `user.manage` | Manage users | MANAGE | UNIT | AVAILABLE | Invite, modify, suspend and deactivate user accounts |

## Staff management

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `staff.read` | View staff | READ | UNIT | AVAILABLE | View staff profiles |
| `staff.manage` | Manage staff | MANAGE | UNIT | AVAILABLE | Create and edit staff profiles and their organizational assignments |

## Role management

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `role.read` | View roles | READ | UNIT | AVAILABLE | View roles, their permissions and who holds them |
| `role.assign` | Assign roles | WRITE | UNIT | AVAILABLE | Assign and revoke roles within your own permissions and scope |
| `role.manage` | Create and edit custom roles | MANAGE | UNIT | AVAILABLE | Create, edit, clone, deactivate and archive custom roles within your own permissions |

## Audit and administration

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `audit.read` | View the audit trail | READ | ORGANIZATION | AVAILABLE | View and verify the tamper-evident audit trail |

## Academic structure

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `academics.read` | View academic structure | READ | UNIT | AVAILABLE | View programs, courses, subjects, the academic calendar, batches and subject offerings |
| `program.manage` | Manage programs and courses | MANAGE | UNIT | AVAILABLE | Create, edit and deactivate programs and their courses or branches |
| `subject.manage` | Manage subjects and curricula | MANAGE | UNIT | AVAILABLE | Create and edit subjects, prerequisites and which terms of a course teach them |
| `academic_calendar.manage` | Manage the academic calendar | MANAGE | UNIT | AVAILABLE | Create and edit academic years and their semesters or terms |
| `batch.manage` | Manage batches and subject offerings | MANAGE | UNIT | AVAILABLE | Create and edit batches and decide which subjects are offered to them in a term |
| `faculty.assign` | Assign faculty | WRITE | UNIT | AVAILABLE | Assign faculty to subject offerings and end assignments |

## Student management

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `student.read` | View students | READ | UNIT | PLANNED | View student records and enrolment |
| `student.manage` | Manage students | MANAGE | UNIT | PLANNED | Create and update student records and lifecycle |

## Admissions

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `admission.read` | View admissions | READ | UNIT | PLANNED | View admission cycles, applicants and screening results |
| `admission.manage` | Manage admissions | MANAGE | UNIT | PLANNED | Configure admission cycles and decide on applicants |

## Assessment management

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `assessment.read` | View assessments | READ | UNIT | PLANNED | View assessments and question banks |
| `assessment.manage` | Author and publish assessments | MANAGE | UNIT | PLANNED | Create, edit and publish assessments and questions |

## Examination management

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `exam.read` | View examinations | READ | UNIT | PLANNED | View examination schedules and sessions |
| `exam.manage` | Conduct examinations | MANAGE | UNIT | PLANNED | Schedule examinations, manage sessions and proctoring |

## Evaluation

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `evaluation.write` | Evaluate answers | WRITE | UNIT | PLANNED | Evaluate allocated answer scripts |
| `evaluation.manage` | Manage evaluation | MANAGE | UNIT | PLANNED | Allocate scripts, moderate and approve evaluation |

## Results and reporting

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `result.read` | View results and reports | READ | UNIT | PLANNED | View results, analytics and reports |
| `result.publish` | Approve and publish results | MANAGE | UNIT | PLANNED | Approve and publish results |

## Placement

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `placement.read` | View placement | READ | UNIT | PLANNED | View placement drives and outcomes |
| `placement.manage` | Manage placement | MANAGE | UNIT | PLANNED | Manage placement drives, recruiters and shortlists |

## AI capabilities

| Code | Name | Level | Scope | Availability | Description |
|---|---|---|---|---|---|
| `ai.use` | Use AI assistance | WRITE | UNIT | PLANNED | Use enabled AI capabilities (output always needs human approval) |
| `ai.approve` | Approve AI-generated output | MANAGE | UNIT | PLANNED | Review and approve AI-generated questions or evaluations |

## Who holds what (system roles)

System roles are immutable through the API. Since increment 1.3 INSTITUTION_ADMIN also holds `role.manage`, bounded by
the ceiling rule (it can only create roles limited to its own units, with permissions it holds there).

Since increment 1.4 (migration `V202611031100`): ORG_ADMIN and INSTITUTION_ADMIN hold all six academic permissions;
DEPARTMENT_HEAD holds `academics.read`, `batch.manage` and `faculty.assign`; CAMPUS_ADMIN, FACULTY, EXAM_CONTROLLER,
MANAGEMENT, EVALUATOR, PLACEMENT_OFFICER and ASSESSMENT_DESIGNER hold `academics.read`. STUDENT and external roles hold none.
The catalogue now has 36 permissions: 19 available, 16 planned, 1 platform-only.

Adding a permission: add the enum entry with its metadata, grant it to system roles in a Flyway migration, and keep
`PermissionCatalogConsistencyIT` and `RoleManagementIT.Catalogue` green.
