# Phase 1 plan: Amendment 1 (product-owner approval of 2026-11-02)

* Status: **Approved** (decisions recorded in §8, 2026-11-02). Records the scope added by the Phase 1 approval and maps it onto the approved increments
  1.0–1.7. Nothing here is implemented. Implementation starts only after the Phase 0 CI pipeline is green.
* Base document: [plan.md](plan.md). Where this amendment and the base plan differ, this amendment wins.

## 1. Approved decisions (now binding)

| ID | Decision | Effect on the plan |
|---|---|---|
| D-P1-01 | Four fixed unit types with configurable display labels | As planned (§3.1 of the base plan) |
| D-P1-02 | Institution-specific SSO deferred to Phase 2 | As planned |
| D-P1-03 | Student login accounts on demand only; creating a student profile never creates a Keycloak account | Explicit "Create login account" action. Covered by a test asserting no IdP call on student create/import |
| D-P1-04 | Dedicated, narrowly scoped Keycloak service account | Confidential client `ivorovi-provisioner` with realm-management roles `manage-users`, `view-users` and `query-users` only. Secret from the secret store, server-side only, never exposed to the frontend (test: no provisioning credentials in config.json or bundles) |
| D-P1-05 | Remove `/institutions` after verifying no external consumers | Verification step in 1.1: repository-wide reference search, access-log check (none in pre-release), OpenAPI diff reviewed, then remove endpoints, client, tests and docs together |

## 2. Scope added by the approval (not in the base plan)

| Area | Addition | Placed in | Design notes |
|---|---|---|---|
| Visual design | Mandatory design system, navy shell, dashboard benchmark, visual checklist | **1.0** (tokens, shell, components, restyle of Phase 0 screens) | [design-system.md](../design/design-system.md), [visual-checklist.md](../design/visual-checklist.md) |
| Shell | Expandable/collapsible nav groups, breadcrumbs, institution selector, global search, notifications, account menu | 1.0 (shell), search sources added per increment | Search: `GET /api/v1/search?q=` returns typed results filtered by permissions |
| Notifications | In-app notification centre (import finished/failed, invitation failed, role expiring) | 1.0 table + API, producers in 1.3/1.4/1.6 | Pulls a minimal part of module 17 forward. Email templates stay in Phase 2 |
| Org hierarchy | Assign administrators from the unit page; per-unit branding (logo, accent colour); organizational history | 1.1 | Branding inherits down the tree; logo in tenant-scoped object storage (PNG/SVG-sanitised/JPEG ≤ 512 kB). History: `org_unit_history` (effective-dated snapshots) + audit |
| Users | Bulk **user** import (CSV/XLSX), faculty profiles, campus/department assignments, account history | 1.3 | Faculty profile = staff profile + teaching attributes (qualification, specialisation, subjects taught). Assignments = `staff_assignment` (staff × org unit × role-in-unit, primary flag, dates) |
| Roles | Role activation/deactivation; scopes **Program** and **Individual resource** in addition to tenant and org units; permission matrix; access management view | 1.4 (program scope enabled once programs exist in 1.5) | `role_assignment.scope_type` ∈ TENANT, ORG_UNIT, PROGRAM, RESOURCE; RESOURCE adds `resource_type` (e.g. BATCH, SUBJECT_OFFERING). Authz resolves program → owning department → org path, so tree semantics stay uniform |
| Academics | **Subjects** and **course-subject mapping** (see D-P1-06); faculty assignments | 1.5 | |
| Students | Department assignment, document management, duplicate detection, profile history | 1.6 | Documents: `student_document` (type, object key, size, content type, checksum, verified_by/at), allow-listed types (PDF, JPEG, PNG) ≤ 5 MB, presigned downloads, audit on view and download. Malware scanning is a port with no scanner yet (known issue; ClamAV adapter candidate) |
| Import | XLSX in addition to CSV; downloadable template; preview; duplicate review; **asynchronous** processing; summary; failed-record report | 1.6 (students), 1.3 (users) | Async via a DB-backed job queue (`import_job` + `SKIP LOCKED` worker, same pattern as the outbox; no new infrastructure). **All-or-nothing commit by default**; optional "import valid rows only" mode that still reports every row's outcome. Never a silent partial commit |
| Feature settings | Activation/deactivation history; explicit inheritance view | 1.1 | History endpoint over audit events `feature.override.changed`. Overrides move from institution to org-unit level (UNIVERSITY/COLLEGE), inheritance down the tree |
| Dashboard | 8 KPIs, distributions, department statistics, activity, recent registrations, notifications, quick actions; filters by institution, campus, department, academic year | Incremental: org KPIs in 1.1, people KPIs in 1.3, academics in 1.5, students in 1.6 | `GET /api/v1/dashboard/summary?orgUnitId=&academicYearId=`, `/dashboard/distributions`, `/dashboard/departments`. Aggregations RLS- and scope-filtered, cached ≤ 60 s per tenant + filter |
| Engineering | Review backend dependency and artifact size | 1.0 | Seeder → `backend:devtools`; drop unused AWS HTTP client; report jar size before and after |

## 3. Updated increments

| Inc. | Content (base plan + additions) | New screens (inventory #) |
|---|---|---|
| **1.0** | Caching, rate limiting, idempotency, code splitting, outbox retention, artifact slimming **+ design tokens, Inter, shell (sidebar, top bar, breadcrumbs, search frame, notifications), component library additions, SVG charts, restyle of Phase 0 screens, visual baselines** | Feature settings (19), Audit logs (20), My Access (21) restyled |
| **1.1** | Org units, migration, ltree authz, terminology **+ branding, history, administrators, institution selector, feature inheritance/history, `/institutions` removal, dashboard v1** | Dashboard (1, partial), Organization hierarchy (2), Institutions (3), Campuses (4), Departments (5), System settings |
| **1.2** | Academic years and terms | Academic years (6) |
| **1.3** | Users, staff, invitations, deactivation **+ faculty profiles, staff assignments, bulk user import, account history, notification producers** | User management (11), Faculty management (12) |
| **1.4** | Permission catalogue, custom roles, assignments, anti-escalation **+ role activation, program/resource scopes, permission matrix, access management** | Roles (16), Permission matrix (17), Access management (18) |
| **1.5** | Programs, courses, curriculum, batches, sections, offerings **+ subjects, course-subject mapping, faculty assignments** | Programs (7), Courses (8), Subjects (9), Batches (10) |
| **1.6** | Students, lifecycle, bulk import **+ registration, documents, duplicate detection, profile history, async XLSX/CSV import wizard, on-demand login, full dashboard** | Students (13), Student profile (14), Bulk import (15), Dashboard (1, complete) |
| **1.7** | Regression, performance, accessibility, docs **+ visual checklist sign-off for all screens at three viewports** | — |

## 4. Additional database objects (beyond base plan §3)

`notification` (user-targeted, read_at), `org_unit_history`, `org_unit.branding jsonb` + `logo_object_key`,
`staff_assignment`, `faculty_profile` (qualification, specialisation, experience_years), `user_status_event`,
`role.active boolean`, `role_assignment.resource_type`/`resource_id` + scope types PROGRAM/RESOURCE,
`subject` (owning department, code, title, credits, L-T-P, type), `course_subject` (course × term × subject,
elective group), `subject_offering` + `subject_offering_instructor` (replacing `course_offering*` per D-P1-06),
`student_document`, `student_profile_event` (field-level change history), `import_job` (+ `kind` USERS/STUDENTS,
`format` CSV/XLSX, `mode` ALL_OR_NOTHING/VALID_ONLY, row counters, report keys), `import_row` (validated row
data during preview, purged after completion), `student_duplicate_candidate`. `pg_trgm` extension (trusted)
for fuzzy duplicate matching. All tenant-owned with RLS.

## 5. Acceptance criteria mapping (product owner's 20 criteria)

| # | Criterion | Verified in | Evidence type |
|---|---|---|---|
| 1–5 | Universities, colleges, campuses, departments; invalid relationships rejected | 1.1 | API integration tests (incl. cycles and invalid parent types), E2E tree workflow |
| 6 | Users created and assigned scopes | 1.3, 1.4 | Integration + E2E |
| 7 | Custom roles created and assigned | 1.4 | Integration + E2E |
| 8 | Backend permission enforcement | every increment | Authorization-matrix tests per endpoint |
| 9 | Faculty and staff management | 1.3 | Integration + E2E with Keycloak invitation via Mailpit |
| 10 | Programs and courses configured | 1.5 | Integration + E2E |
| 11 | Students registered | 1.6 | Integration + E2E |
| 12 | Bulk student import | 1.6 | 5,000-row CSV and XLSX imports, error and duplicate scenarios, async completion |
| 13 | Login accounts on demand | 1.6 | E2E: create account → email → student signs in; test that profile creation makes no IdP call |
| 14 | Cross-tenant access blocked | every increment | Extended cross-tenant suite (API + RLS + caches) |
| 15 | Dashboard from real data | 1.1 → 1.6 | E2E compares KPI values with DB counts after seeded changes |
| 16 | Visual design match | 1.0 → 1.7 | Visual checklist + screenshot baselines at three viewports |
| 17 | All tests pass | 1.7 | Local + CI |
| 18 | GitHub CI passes | 1.7 | CI run reference |
| 19 | Phase 0 workflows still work | every increment | Phase 0 suites kept and adapted only where APIs were intentionally renamed (`/institutions`) |
| 20 | Documentation and tracker updated | every increment | Docs in each increment's commit |

## 6. New dependencies requested (no new infrastructure)

| Dependency | Licence | Why | Alternative considered |
|---|---|---|---|
| `org.dhatim:fastexcel` + `fastexcel-reader` | Apache-2.0 | XLSX template generation and streaming read for imports | Apache POI: larger (~15 MB) and conflicts with the artifact-size goal |
| `com.bucket4j:bucket4j_jdk17-lettuce` | Apache-2.0 | Distributed rate limiting on existing Valkey (already in base plan) | Hand-written Lua token bucket |
| `@fontsource-variable/inter` | OFL-1.1 | Self-hosted Inter | System font stack (does not meet the visual benchmark) |

Charts are built as small SVG components in `packages/ui`, so no chart library is added.

## 7. Decisions needed

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **D-P1-06** | Meaning of **Program / Course / Subject**. Indian institutions use these words differently. The approval lists program-course and course-subject mappings | (a) **Program** = degree (B.Tech) → **Course** = branch/specialisation offered by a department (Computer Science & Engineering) → **Subject** = paper with credits (Data Structures). Batches belong to a course; faculty are assigned to subject offerings. (b) Program = B.Tech CSE → Course = paper → Subject = unit/module within a paper | **(a)**. It matches both mappings in the approval and common university/affiliated-college usage. Display labels stay configurable like unit types |
| D-UI-01 | Navy sidebar (spec) vs white sidebar (R1) | See design system §6 | Navy sidebar on desktop, navy top bar on tablet/mobile |
| D-UI-02 | Font | Inter self-hosted vs system stack | Inter self-hosted |
| D-UI-03 | Dark mode in Phase 1 | Keep unreviewed vs remove | Remove for Phase 1 |
| D-P1-07 | Import commit semantics | All-or-nothing only vs + "valid rows only" | Both, all-or-nothing default; both always produce a full per-row report |
| D-P1-08 | Student document malware scanning | Ship without scanner (allow-list + size limits) vs add ClamAV now | Port now, scanner adapter before first production tenant (new infrastructure, needs separate approval) |

## 8. Decisions recorded (product owner, 2026-11-02)

| ID | Decision | Binding conditions |
|---|---|---|
| D-P1-06 | **Program → Course → Subject** approved | Program (B.Tech, MBA, BCA, MCA) has duration (terms) and qualification level. Course/Branch (CSE, Mechanical) maps to one owning department. Subject/Paper (Data Structures, Operating Systems) has credits, and its semester placement lives in the course-subject mapping. A batch is mapped to a course and an intake academic year. Faculty are assigned to subject offerings per academic session (term). Display labels are configurable per tenant; **internal entity types (`PROGRAM`, `COURSE`, `SUBJECT`) are stable** and label changes never alter data semantics |
| D-UI-01 | Navy desktop sidebar, navy top bar on tablet/mobile | Keep R1's layout and components under the navy identity |
| D-UI-02 | Self-hosted Inter | — |
| D-UI-03 | Dark mode deferred | Reconsider only after the light theme passes visual and accessibility checks |
| D-P1-07 | Import modes: all-or-nothing (default) and valid-rows-only | **Duplicate detection runs against existing students** (DB), not only within the file. **Imports are idempotent:** the same request (Idempotency-Key) or a re-submitted identical file (SHA-256) for the same target returns the existing job, and row commits are keyed by natural identifiers (enrollment number), so a retry can never create duplicate students |
| D-P1-08 | Document scanning, conditional | See [ADR-0006](../adr/0006-document-malware-scanning.md): validation ≠ malware protection; quarantine + scan state; fail closed in production; ClamAV or equivalent before the first real college uploads documents |
| D-P1-09 | New libraries | `@fontsource-variable/inter` approved (with the font decision). **fastexcel and Bucket4j were not approved:** rate limiting is implemented with a token-bucket script on the existing Valkey (no new dependency); XLSX support (fastexcel) will be requested again before increment 1.6 |
