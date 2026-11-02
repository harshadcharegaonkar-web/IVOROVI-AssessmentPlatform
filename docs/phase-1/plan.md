# Phase 1 implementation plan: organization structure, people and platform hardening

* Status: **Draft for approval** (2026-11-02). Nothing in this document is implemented yet.
* Starts after: the first green GitHub Actions run (Phase 0 criterion 9) and approval of this plan, including
  the decisions in §9.
* Principle: extend, do not restructure. The modular monolith, tenant context + RLS, server-side scoped
  authorization, audit-in-transaction, outbox, problem-details errors and the test pyramid stay as they are. The
  only structural change is that `institution` generalizes into an organization-unit tree (§3.1). This is the
  minimum needed for university → college → campus → department.

## 1. Scope (priority order from the product owner)

| # | Capability | Module(s) |
|---|---|---|
| 1 | Institution hierarchy: university, college, campus, department | `tenancy` |
| 2 | User and staff management | `identity` |
| 3 | Custom roles and permission administration | `identity` |
| 4 | Programs, courses, batches, academic years | new `academics` (structure part only; grading stays Phase 7) |
| 5 | Student lifecycle management | new `people` |
| 6 | Tenant-aware caching | `platform` |
| 7 | API rate limiting | `platform` |
| 8 | Idempotency support | `platform` |
| 9 | Frontend code splitting and performance | `frontend` |

Out of scope: admissions/candidates (Phase 5), grading policies and marks (Phase 7), SSO federation and
just-in-time account linking (see D-P1-02), and notification templates beyond the invitation email (Phase 2).

## 2. Implementation sequence and dependencies

Each increment ships with migration, API, UI, authorization, tests and docs, plus a demo before the next one starts.

| Inc. | Content | Depends on | Demo (exit) |
|---|---|---|---|
| **1.0 Platform hardening** | Tenant-aware cache layer (principal, tenant, org-tree caches with after-commit eviction); rate limiting; idempotency keys; route-level code splitting and bundle budget; outbox retention job; seeder split out of the production jar | Phase 0 | Requests served with 0 DB lookups when cached; 429 with `Retry-After`; replayed POST returns the stored response; initial JS ≤ 120 kB gzip |
| **1.1 Organization hierarchy** | `org_unit` tree (UNIVERSITY, COLLEGE, CAMPUS, DEPARTMENT), migration of `institution`, ltree-based scope checks, tenant terminology settings, tree management UI | 1.0 (org-tree cache) | Org admin builds University → College → Campus → Department; a department-scoped grant works only inside that subtree |
| **1.2 Academic calendar** | Academic years and terms (semester/trimester/annual) per university or college, with one "current" year | 1.1 | Create 2026-27 with two semesters; mark current |
| **1.3 Users and staff** | User administration, staff profiles, invitations via Keycloak (provisioning port + outbox-driven retries), deactivate/reactivate with session revocation | 1.1 | Org admin invites a staff member, who receives the email in Mailpit, sets a password and signs in |
| **1.4 Roles and permissions** | Permission catalog API, custom roles, scoped and time-bound assignments, anti-escalation rules, last-admin protection | 1.1, 1.3 | Create "Exam Cell Assistant" role, assign it at college scope, and show that escalation attempts are refused and audited |
| **1.5 Programs, courses, batches** | Programs (owned by a department), course catalog, curriculum (program × term × course), batches/sections per intake year, course offerings with instructors | 1.1, 1.2, 1.3 | Define B.Tech CSE (8 semesters) with curriculum, a 2026 batch with sections A/B, and offer courses for semester 1 with faculty assigned |
| **1.6 Student lifecycle** | Student records, enrollment into program/batch/section, lifecycle state machine with history, bulk CSV import with validation report, optional login invitations | 1.3, 1.5 | Import 500 synthetic students (with errors reported per row), promote them to semester 2, mark one as on leave, graduate a batch |
| **1.7 Regression and hardening** | Full regression suite, performance check (authz with cache, list endpoints with 10k rows), accessibility pass, docs, Phase 1 report | all | Phase 1 acceptance criteria (§7) demonstrated |

```
1.0 ──► 1.1 ──► 1.2 ──┐
          │           ├──► 1.5 ──► 1.6 ──► 1.7
          └──► 1.3 ──► 1.4 ──────────────┘
                 └────────────────► 1.6
```

## 3. Database changes (Flyway, owner role; every tenant table has `tenant_id` + RLS)

### 3.1 `tenancy`: organization units (increment 1.1)

```sql
CREATE EXTENSION IF NOT EXISTS ltree;           -- trusted extension, creatable by the DB owner (PG ≥ 13)

CREATE TABLE org_unit (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
  parent_id uuid, unit_type varchar(20) NOT NULL CHECK (unit_type IN ('UNIVERSITY','COLLEGE','CAMPUS','DEPARTMENT')),
  code varchar(63) NOT NULL, name varchar(200) NOT NULL, short_name varchar(50),
  status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  path ltree NOT NULL,                          -- maintained by the service in the same transaction
  attributes jsonb NOT NULL DEFAULT '{}',       -- address, affiliation number, accreditation, etc. (validated per type)
  created_at/by, updated_at/by, version,
  UNIQUE (tenant_id, code), UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, parent_id) REFERENCES org_unit (tenant_id, id)
);
CREATE INDEX org_unit_path_gist ON org_unit USING gist (path);
-- RLS: tenant_isolation as in Phase 0
```

Allowed parent types (enforced in the service and covered by tests): UNIVERSITY → (root); COLLEGE → root or
UNIVERSITY; CAMPUS → UNIVERSITY or COLLEGE; DEPARTMENT → UNIVERSITY, COLLEGE or CAMPUS. Moving a subtree
rewrites `path` for all descendants in one transaction and is audited.

**Migration of Phase 0 data:** every `institution` row becomes an `org_unit` of type COLLEGE with the **same
UUID**. `feature_flag_override.institution_id` is renamed to `org_unit_id` (FK to `org_unit`).
`role_assignment.scope_type` 'INSTITUTION' becomes 'ORG_UNIT'. The `institution` table is dropped. A migration
test loads the Phase 0 fixture, migrates, and asserts that grants and overrides behave identically.

`tenant` gains `settings jsonb` (terminology labels per unit type, e.g. "School" instead of "College"; time zone;
locale; academic-year naming pattern). There is no hardcoded institution data.

### 3.2 `identity`: users, staff, roles (increments 1.3, 1.4)

```sql
ALTER TABLE user_account ADD COLUMN given_name varchar(100), ADD COLUMN family_name varchar(100),
  ADD COLUMN phone varchar(30), ADD COLUMN user_type varchar(20) NOT NULL DEFAULT 'STAFF'
    CHECK (user_type IN ('STAFF','STUDENT','EXTERNAL')),
  ADD COLUMN idp_user_id varchar(255), ADD COLUMN provisioning_status varchar(20) NOT NULL DEFAULT 'LINKED'
    CHECK (provisioning_status IN ('PENDING','INVITED','LINKED','FAILED')),
  ADD COLUMN invited_at timestamptz, ADD COLUMN deactivated_at timestamptz;
-- external_subject becomes nullable until the IdP account exists; partial unique index where not null.

CREATE TABLE staff_profile (
  user_account_id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  employee_no varchar(50), designation varchar(100),
  primary_org_unit_id uuid NOT NULL, employment_type varchar(20) CHECK (employment_type IN ('PERMANENT','CONTRACT','VISITING','ADJUNCT')),
  joined_on date, left_on date, version bigint,
  UNIQUE (tenant_id, employee_no), FK (tenant_id, user_account_id) → user_account, FK (tenant_id, primary_org_unit_id) → org_unit
);

ALTER TABLE role ADD COLUMN assignable_scopes text[] NOT NULL DEFAULT '{TENANT,ORG_UNIT}',
                 ADD COLUMN archived_at timestamptz;
ALTER TABLE role_assignment ADD COLUMN revoked_at timestamptz, ADD COLUMN revoked_by uuid,
                            ADD COLUMN revocation_reason varchar(500);   -- soft revoke keeps history
```

### 3.3 `academics`: structure (increments 1.2, 1.5)

| Table | Key columns | Notes |
|---|---|---|
| `academic_year` | org_unit_id (UNIVERSITY/COLLEGE), code (`2026-27`), starts_on, ends_on, status (PLANNED/CURRENT/CLOSED) | At most one CURRENT per org unit (partial unique index). Ranges may not overlap (exclusion constraint) |
| `academic_term` | academic_year_id, sequence, name, term_type (SEMESTER/TRIMESTER/ANNUAL), starts_on, ends_on | Within the year; no overlaps |
| `program` | owning_org_unit_id (DEPARTMENT), code, name, level (CERTIFICATE/DIPLOMA/UG/PG/DOCTORAL), duration_terms, total_credits, status | |
| `course` | owning_org_unit_id, code, title, credits numeric(4,1), lecture/tutorial/practical hours, course_type (CORE/ELECTIVE/LAB/PROJECT), status | Catalog entry, independent of programs |
| `program_course` | program_id, term_number, course_id, is_elective, elective_group | The curriculum |
| `batch` | program_id, intake_academic_year_id, code (`BTECH-CSE-2026`), current_term_number, status (ACTIVE/GRADUATED/CLOSED) | |
| `section` | batch_id, code (`A`), capacity | |
| `course_offering` | course_id, academic_term_id, batch_id, section_id null, status | |
| `course_offering_instructor` | course_offering_id, user_account_id (staff), role (PRIMARY/CO_INSTRUCTOR/TA) | Basis for faculty-scoped assessment permissions in Phase 3 |

All tables are tenant-owned with RLS, composite tenant FKs and `UNIQUE (tenant_id, code)` where a code exists.

### 3.4 `people`: students (increment 1.6)

| Table | Key columns | Notes |
|---|---|---|
| `student` | user_account_id null (login optional), enrollment_no, given/family name, date_of_birth, gender (optional), email, phone, primary_org_unit_id, status | No government IDs in Phase 1 (DPDP data minimization) |
| `student_enrollment` | student_id, program_id, batch_id, section_id, academic_year_id, current_term_number, enrolled_on, ended_on, end_reason | One open enrollment per student (partial unique) |
| `student_status_event` | student_id, from_status, to_status, effective_on, reason, actor_id, created_at | Append-only lifecycle history |
| `import_job` | kind (STUDENTS/STAFF), file object key, status, totals, error report key, created_by | File and error report live in tenant-scoped object storage |

Lifecycle states: `ENROLLED → ACTIVE ⇄ ON_LEAVE`, `ACTIVE → SUSPENDED → ACTIVE`, `ACTIVE → DETAINED → ACTIVE`
(repeat term), terminal `GRADUATED | WITHDRAWN | TRANSFERRED_OUT | EXPELLED`. Each transition has a required
reason (except promotion), a permission, and an audit event. Promotion advances the term number for a whole
batch or section in one audited, idempotent operation.

### 3.5 `platform` (increment 1.0)

```sql
CREATE TABLE idempotency_record (
  tenant_id uuid NOT NULL, idem_key varchar(100) NOT NULL, user_account_id uuid NOT NULL,
  method varchar(10) NOT NULL, path varchar(300) NOT NULL, request_hash char(64) NOT NULL,
  status varchar(15) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_status int, response_body bytea, response_headers jsonb,
  created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, user_account_id, idem_key)
);  -- RLS; retention job deletes expired rows (24 h default)
```

## 4. Platform designs (increment 1.0)

**Tenant-aware caching.**
- A `TenantCache` abstraction over Valkey forces keys of the form `ivorovi:{cache}:t:{tenantId}:{key}`. The tenant comes from `TenantContext`, so a caller cannot supply another tenant's ID.
- Caches:
  - **principal** (tenant, subject) → account and grants; TTL 60 s, evicted on any user, role or assignment change.
  - **tenant directory**; TTL 5 min, evicted on status change.
  - **org-unit paths and tree**; evicted on hierarchy change.
  - **feature overrides** (existing).
- Evictions run after commit and are also published as outbox events, so multiple API replicas converge.
- Tests cover: no cross-tenant hits, eviction on change, and that revocation takes effect on the next request in the same replica and within the TTL bound on others.

**Rate limiting.**
- Bucket4j (Apache-2.0) token buckets stored in Valkey, applied by a filter after authentication.
- Keys: per user, per tenant, and per client IP for unauthenticated requests.
- Classes (configurable via `ivorovi.rate-limit.*`):

  | Class | Limit |
  |---|---|
  | read | 300/min per user |
  | write | 60/min per user |
  | bulk | 5/min per user |
  | tenant ceiling | 3,000/min |
  | unauthenticated | 30/min per IP |

- Responses: 429 problem `rate_limited` with `Retry-After` and `RateLimit-*` headers.
- If Valkey is down, the limiter fails open but emits an alert metric.
- Deployment profiles can raise the limits; tests use dedicated low limits.

**Idempotency.**
- `@Idempotent` on create, submit and bulk endpoints. The `Idempotency-Key` header is **required** on those endpoints in Phase 1.
- Same key and same request hash: the stored response is replayed (header `Idempotency-Replayed: true`).
- Same key with a different hash: 422 `idempotency_key_reused`.
- Concurrent duplicate while the first is in progress: 409 `idempotency_in_progress`.
- Records are scoped per tenant and user and stored under RLS.

**Frontend performance.**
- React Router lazy routes per area, with a vendor chunk split.
- `ChunkLoadError` recovery: reload once, then show an error state.
- Hover/viewport prefetch for navigation links.
- CI bundle budget: initial JS ≤ 120 kB gzip, any route chunk ≤ 80 kB gzip. The build fails above budget.

**Build.** The seeder moves into a separate `backend:devtools` Gradle project. The production jar no longer
includes Datafaker; `scripts/seed.sh` runs the devtools jar.

## 5. API specification (all under `/api/v1`, problem+json errors, OpenAPI generated)

Conventions: list endpoints take `q`, `page`, `size` (≤ 100), `sort`, `direction`. Writes use optimistic locking
(`version` in the body; 409 `concurrent_modification` on conflict). "Idem" means `Idempotency-Key` is required.

### 5.1 Organization (1.1)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/org-units?parentId=&type=&q=` | `org_unit.read` (scope-filtered) | Paged; `parentId=root` for top level |
| GET | `/org-units/tree?rootId=&depth=` | `org_unit.read` | Nested tree limited to visible subtrees |
| GET | `/org-units/{id}` | `org_unit.read` in scope | Includes ancestors (breadcrumb) |
| POST | `/org-units` | `org_unit.manage` on parent (tenant for roots) | Idem; validates parent-type rules |
| PATCH | `/org-units/{id}` | `org_unit.manage` in scope | Name, short name, attributes, status |
| POST | `/org-units/{id}/move` | `org_unit.manage` on source and target | Rewrites subtree paths |
| GET/PATCH | `/tenant/settings` | `tenant.settings.manage` | Terminology, time zone, locale |

Phase 0's `/institutions` endpoints are removed (no external consumers yet). The web app moves to `/org-units`.

### 5.2 Academic calendar (1.2)

| Method | Path | Permission |
|---|---|---|
| GET/POST | `/academic-years?orgUnitId=` | read: authenticated in scope · create: `academic_calendar.manage` (idem) |
| GET/PATCH | `/academic-years/{id}` | as above |
| POST | `/academic-years/{id}/make-current` | `academic_calendar.manage` |
| GET/POST | `/academic-years/{id}/terms` | as above |
| PATCH/DELETE | `/academic-terms/{id}` | `academic_calendar.manage` (delete only if unused) |

### 5.3 Users and staff (1.3)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/users?type=&status=&orgUnitId=&q=` | `user.read` (scope-filtered by primary unit) | |
| POST | `/users` | `user.manage` at target unit | Idem; creates account, staff profile and IdP user; status `PENDING` → `INVITED` |
| GET/PATCH | `/users/{id}` | `user.read` / `user.manage` | Email change re-verifies via IdP |
| POST | `/users/{id}/invitation` | `user.manage` | Re-send (rate class: bulk) |
| POST | `/users/{id}/deactivate` · `/reactivate` | `user.manage` | Disables the IdP user and revokes sessions; principal cache evicted |
| GET | `/users/{id}/role-assignments` | `user.read` | |

### 5.4 Roles and permissions (1.4)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/permissions` | `role.read` | Catalog with description, group and assignable scopes |
| GET | `/roles?includeSystem=` | `role.read` | |
| POST | `/roles` | `role.manage` | Idem; custom roles only |
| GET/PATCH | `/roles/{id}` | `role.read` / `role.manage` | System roles immutable (409) |
| POST | `/roles/{id}/archive` | `role.manage` | Only if no active assignments |
| POST | `/role-assignments` | `role.assign` at the scope | Idem. **Anti-escalation**: the granter must hold every permission of the role at that scope; no self-assignment |
| POST | `/role-assignments/{id}/revoke` | `role.assign` at the scope | Reason required; last active ORG_ADMIN cannot be revoked |

### 5.5 Programs, courses, batches (1.5)

| Method | Path | Permission |
|---|---|---|
| GET/POST, GET/PATCH | `/programs`, `/programs/{id}` | read: `academics.read` · write: `program.manage` at owning department |
| GET/PUT | `/programs/{id}/curriculum` | `program.manage` (whole-curriculum replace with version check) |
| GET/POST, GET/PATCH | `/courses`, `/courses/{id}` | `academics.read` / `course.manage` |
| GET/POST, GET/PATCH | `/batches`, `/batches/{id}` | `academics.read` / `batch.manage` |
| GET/POST | `/batches/{id}/sections` | `batch.manage` |
| GET/POST, PATCH | `/course-offerings?termId=&batchId=`, `/course-offerings/{id}` | `academics.read` / `course.manage` |
| PUT | `/course-offerings/{id}/instructors` | `course.manage` |

### 5.6 Students (1.6)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/students?programId=&batchId=&sectionId=&status=&q=` | `student.read` (scope-filtered) | |
| POST | `/students` | `student.manage` | Idem |
| GET/PATCH | `/students/{id}` | `student.read` / `student.manage` | A student may GET only their own record via `/me/student` |
| POST | `/students/{id}/transitions` | `student.lifecycle` | Body: target status, effective date, reason. 409 on an invalid transition |
| GET | `/students/{id}/history` | `student.read` | |
| POST | `/batches/{id}/promotions` | `student.lifecycle` | Idem; advances term for ACTIVE students |
| POST | `/imports/students` (multipart CSV ≤ 5 MB / 10k rows) | `student.import` | Idem; validates all rows; `mode=validate|commit` |
| GET | `/imports/{id}` · `/imports/{id}/errors` | `student.import` | Error report as CSV via presigned URL |
| POST | `/students/{id}/invitation` | `student.manage` | Optional login for students (see D-P1-03) |

### 5.7 Permission catalog additions

`org_unit.read`, `org_unit.manage` (rename of `institution.*`), `tenant.settings.manage`, `role.read`,
`role.assign`, `academic_calendar.manage`, `academics.read`, `program.manage`, `course.manage`,
`batch.manage`, `student.read`, `student.manage`, `student.lifecycle`, `student.import`. System-role
grants are updated in the same migrations (e.g. FACULTY → `academics.read`, `student.read`; DEPARTMENT_HEAD →
`program.manage`, `course.manage` at department scope).

## 6. Frontend screen inventory

| Area | Screen | Key interactions | States |
|---|---|---|---|
| Organization | Org structure (tree + detail split view) | Expand/collapse, search, add child (type-aware), edit, deactivate, move (dialog with target picker), breadcrumb | Loading skeleton tree, empty org onboarding, error, no-permission |
| Organization | Org unit detail | Tabs: overview, features (existing panel), academic years, programs, people | as above |
| Settings | Organization settings | Terminology labels, time zone, locale | Dirty-form guard |
| Calendar | Academic years and terms | Create year wizard with terms, make current, edit dates | Overlap validation inline |
| People | Staff directory | Filter by unit, status and role; invite (form), bulk actions (deactivate) | Invite pending/failed badges |
| People | User detail | Profile, staff details, role assignments (assign/revoke dialogs), activity (audit filtered) | |
| Access | Roles list and role editor | Permission picker grouped by area with descriptions; system roles read-only; archive | Escalation errors surfaced inline |
| Academics | Programs list/detail | Curriculum grid (term × course) editor with drag-free add/remove, elective groups | Unsaved-changes guard |
| Academics | Course catalog | CRUD, filters by department and type | |
| Academics | Batches and sections | Create batch from program + intake year, sections, promotion action (confirm dialog with counts) | |
| Academics | Course offerings | Per term: generate offerings from curriculum for a batch, assign instructors | |
| Students | Student list | Filters, columns chooser, CSV export of current view | 10k-row performance via server paging |
| Students | Student detail | Profile, enrollment, lifecycle actions (state-aware buttons), history timeline | |
| Students | Bulk import | Upload → validation summary → per-row errors (downloadable) → commit | Progress for large files |
| Student self-service | My profile (student) | Read-only own record | |

Navigation stays permission-driven. Every list uses `DataTable` with URL-synced filters. Every write shows
success or failure toasts carrying the server's problem message.

## 7. Phase 1 acceptance criteria

1. An org admin can model University → College → Campus → Department. Invalid parent types are rejected (server-side).
2. Scoped grants follow the tree: a department-scoped grant authorizes within that department only. Verified for every new endpoint by an authorization-matrix test (role × scope × endpoint).
3. Phase 0 data migrates without losing grants or feature overrides (migration test).
4. Staff can be invited, receive the IdP email, sign in, be deactivated (sign-in then fails and existing tokens are rejected on the next request) and reactivated.
5. Custom roles can be created and assigned. Escalation attempts (granting permissions the granter lacks, self-assignment, revoking the last org admin, modifying system roles) are refused with 403/409 and audited.
6. Academic years, terms, programs, curriculum, courses, batches, sections and offerings can be created and edited, with overlap and uniqueness rules enforced by the DB and the API.
7. Students can be created and bulk-imported (≥ 5,000 rows in < 60 s). Every invalid row is reported without partial commits in `commit` mode. Lifecycle transitions follow the state machine and are recorded in history and audit.
8. Cross-tenant suite extended to every new table and endpoint: no data visible or modifiable across tenants (API and RLS).
9. Caching: steady-state authenticated requests perform 0 DB lookups for principal resolution. A role revocation takes effect on the next request on the same replica.
10. Rate limiting returns 429 with `Retry-After` at configured thresholds. Idempotent endpoints replay, reject reuse and handle concurrency as specified.
11. Initial JS ≤ 120 kB gzip, enforced in CI. No route regressions in E2E. The axe scan stays clean on all new screens.
12. All tests pass in GitHub Actions. OpenAPI, tracker, ADRs, runbooks and known-issues register are updated. Each workflow is demonstrated with a Playwright scenario.

## 8. Regression risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Institution → org-unit migration breaks grants, feature overrides, fixtures, smoke and E2E tests | High | Same UUIDs; dedicated migration test; update dev realm/fixtures, smoke script and E2E in the same increment; run Phase 0 suites unchanged before refactoring them |
| Caching serves stale authorization after revocation | High | After-commit eviction + outbox broadcast + 60 s TTL ceiling; explicit revocation tests; cache keys derived from `TenantContext` only |
| Cross-tenant cache poisoning | High | `TenantCache` API without a tenant parameter; property-based test across two tenants |
| IdP provisioning partially fails (DB row without IdP user or vice versa) | Medium | Outbox-driven provisioning with idempotent retries, `provisioning_status` visible in UI, reconciliation job, no IdP calls inside DB transactions |
| Rate limits break E2E or CI or bulk operations | Medium | Profile-specific limits; bulk class for imports; tests assert headers |
| Idempotency store leaks response data | Medium | Only annotated endpoints; RLS; 24 h expiry; no storage of file payloads (hash only) |
| ltree queries degrade list performance | Medium | GiST index; scope resolution cached; performance test with 2,000 units / 10,000 students |
| Code splitting causes chunk-load failures after deploys | Medium | Hashed chunks + immutable caching (already), one-time reload on `ChunkLoadError`, E2E for deep links |
| Permission rename (`institution.*` → `org_unit.*`) misses a check | Medium | Catalog consistency test (exists); grep gate in CI for removed keys; authz-matrix tests |
| Scope creep into grading/admissions | Medium | Out-of-scope list (§1); changes require a plan update |

## 9. Decisions needed before implementation

| ID | Question | Recommendation |
|---|---|---|
| D-P1-01 | Fixed unit types (UNIVERSITY, COLLEGE, CAMPUS, DEPARTMENT) with tenant-configurable labels, or fully custom types? | **Fixed types + configurable labels.** Keeps rules and authorization predictable; covers Indian university/affiliated-college structures |
| D-P1-02 | SSO federation (institution IdPs) and just-in-time account linking in Phase 1? | **Defer to Phase 2.** Phase 1 provisions Keycloak-local accounts; the provisioning port keeps federation pluggable |
| D-P1-03 | Create login accounts for all imported students automatically? | **No: on demand** (per student or per batch invitation). Avoids mass emails and dormant accounts until exams need them (Phase 4) |
| D-P1-04 | Keycloak admin access for provisioning | A dedicated confidential client `ivorovi-provisioner` with only `manage-users` in the realm; secret from the secret store |
| D-P1-05 | Remove `/institutions` endpoints now (pre-release) instead of keeping aliases | **Remove**. No external consumers exist yet |
