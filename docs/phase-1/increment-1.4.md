# Increment 1.4: academic structure management

* Status: **Implemented and verified; awaiting product-owner review** (authorized 2026-11-03 with the 1.3 acceptance). Increment 1.5 not started.
* Depends on: 1.3 (accepted). Data model: `docs/architecture/04-academic-data-model.md`.

## Scope delivered

| Area | Delivered |
|---|---|
| Hierarchy | Program → Course (branch) → Subject; labels configurable in System Settings (Program, Course, Subject, Semester/term, Batch) without changing internal types |
| Programs | Code, name, qualification level, awarded qualification, owning university/college, term system (semester, trimester, quarter, annual, other with N terms/year), duration, total terms; activate/deactivate with reason |
| Courses | Code, name, parent program, owning department (inside the program's institution), specialization, optional shorter duration in terms; activate/deactivate |
| Subjects | Code, name, description, credits, theory/practical/both, elective/mandatory default, category; prerequisites (no cycles); curriculum = term applicability per course; one master reused across courses |
| Academic years | Organization-wide or per university/college; dates; PLANNED → ACTIVE → COMPLETED (corrections need a reason); overlap refused unless allowed in System Settings |
| Terms | Any number per year (1–12), name, dates, registration and examination windows; inside the year, never overlapping |
| Batches | Name, code, course, academic year, admission year, expected graduation (defaults from the program duration), section, capacity, status; several per course |
| Subject offerings | Subject, course, batch, year, term, course term, section (defaults to the batch's), credits (default from the subject), status; must be in the curriculum; duplicates refused; cancelling ends faculty assignments |
| Faculty allocation | Existing staff profiles (no separate faculty identity); active teaching staff of the institution; multiple faculty, one primary, start/end dates, ending with reason kept as history; per-staff teaching view |
| Organizational mapping | Tenant / university / college / department ownership with valid combinations; units owning academic records cannot be deleted or moved |
| Screens (14) | Academic overview dashboard; program listing/details; create/edit program; course listing/details (curriculum by term, batches); create/edit course; subject listing/details (prerequisites, used in); create/edit subject; academic year management (+ year detail with its terms); semester management; batch listing/details (offerings); create/edit batch; subject offering management; faculty assignment (offering detail); academic structure tree. Search, filters, pagination, sorting, breadcrumbs, loading/empty/error states, validation, confirmations, responsive layouts |
| Dashboard KPIs | Active programs, courses, subjects, academic years, terms, batches; offerings (and without faculty); faculty assignments (and staff teaching) — all from the caller's authorized data, cached and invalidated on every change |

## Permissions

Six new permissions in the *Academic structure* domain (`docs/security/permission-catalogue.md`): `academics.read`,
`program.manage`, `subject.manage`, `academic_calendar.manage`, `batch.manage`, `faculty.assign`. The migration grants them to
system roles (organization and institution administrators: all; department heads: read, batches/offerings, faculty; other
staff roles: read). Custom roles can include them under the 1.3 ceiling rules.

## APIs (`/api/v1/academics`)

| Resource | Endpoints |
|---|---|
| Programs | `GET/POST /programs`, `GET/PATCH /programs/{id}`, `POST /programs/{id}/status` |
| Courses | `GET/POST /courses`, `GET/PATCH /courses/{id}`, `POST /courses/{id}/status`, `GET/POST /courses/{id}/curriculum`, `DELETE /courses/{id}/curriculum/{entryId}` |
| Subjects | `GET/POST /subjects`, `GET/PATCH /subjects/{id}`, `POST /subjects/{id}/status`, `POST /subjects/{id}/prerequisites`, `DELETE /subjects/{id}/prerequisites/{prerequisiteId}` |
| Calendar | `GET/POST /academic-years`, `GET/PATCH /academic-years/{id}`, `POST /academic-years/{id}/status`, `POST /academic-years/{id}/terms`, `GET /academic-terms`, `GET/PATCH /academic-terms/{id}`, `POST /academic-terms/{id}/status` |
| Batches | `GET/POST /batches`, `GET/PATCH /batches/{id}`, `POST /batches/{id}/status` |
| Offerings | `GET/POST /subject-offerings`, `GET /subject-offerings/{id}`, `POST /subject-offerings/{id}/status` |
| Faculty | `GET /subject-offerings/{id}/faculty?includeHistory=`, `GET /subject-offerings/{id}/faculty-candidates`, `POST /subject-offerings/{id}/faculty`, `POST …/faculty/{assignmentId}/primary`, `POST …/faculty/{assignmentId}/end`, `GET /staff/{staffProfileId}/teaching` |
| Overview | `GET /overview` (KPIs), `GET /tree` |

All creates require `Idempotency-Key`; lists support `q`, filters, `page`, `size` (≤ 100), `sort`, `direction`. The OpenAPI
document (`docs/api/openapi.json`) and the generated TypeScript client were regenerated.

## Results

| Check | Result |
|---|---|
| Backend (JUnit + Testcontainers, real PostgreSQL/Valkey) | **166 tests**, 0 failed — incl. `AcademicsIT` (12: lifecycle, invalid structure, calendar rules, overlap setting, batches/offerings/faculty with history, college and department scope, faculty read-only, cross-tenant isolation, KPIs/tree/cache refresh, delete/move protection, concurrent duplicates/overlaps/primaries, migration grants and RLS), `ModularityTest`, all 1.0–1.3 suites |
| Smoke test with real Keycloak tokens | **51 passed** (adds overview for admin and faculty, student denied, college admin creates in own college but not organization-wide years, faculty denied, cross-tenant 404, deactivation) |
| Frontend unit tests / typecheck / lint | **31** web tests passed (adds `academicsModel.test.ts`); clean |
| Bundle | initial **178.0 kB gzip** (≤ 180 kB; KI-37); every academic page is a lazy chunk; largest lazy chunk 35.8 kB |
| Playwright 1440×900 / 1024×768 / 390×844 | **75 passed** (6 new: full program → course → subject → curriculum → calendar → batch → offering → faculty flow with history; lists/filters/sorting on every academic list; college administrator limited to own college; terminology rename; no horizontal scrolling on tablet and mobile); 1.0–1.3 suites unchanged and passing |
| Accessibility | axe WCAG 2.1 AA: no serious/critical violations on overview, program list/editor/detail, subject editor, course curriculum, calendar, offerings, faculty assignment, structure tree and every list |
| Migration | Development database (1.3 data) upgraded to `202611031100` in 0.28 s; 628 accounts, 621 role assignments, 15 system roles, 18 units and 10 staff profiles unchanged; 10 new tables with RLS enabled |
| GitHub Actions | run 41604859356 on `97cf7f1` (see tracker) |

Acceptance criteria 1–21: functional criteria 1–13 → `AcademicsIT` groups `Structure`, `Calendar`, `Delivery`, `ScopeAndRbac`,
`OverviewAndIntegrity` and the Playwright flow; 14 real-token tests → smoke; 15–17 Playwright at three viewports; 18 axe; 19
regressions (all 1.0–1.3 suites); 20 migration evidence above; 21 CI.

### Hardening and defects found during development
* Overlap checks of academic years are serialised per owner with an advisory lock, and every mutation locks its row before the
  optimistic version check, so simultaneous requests cannot slip past the rules (`concurrentDuplicatesAndOverlapsAreSerialised`).
* Moving an organization unit could have silently broken the course → institution rule; moves of units owning academic records
  are now refused (`org_unit_has_academic_records`).
* Two academic E2E selectors were wrong (double history navigation, KPI test id on the value only) — test fixes, no product change.

### Not delivered / deviations
* Student enrolment into batches is not part of 1.4 (capacity is recorded, not enforced, until students exist).
* Programs, courses, subjects and batches cannot move to another owner/program/course after creation (create a new one);
  this keeps offerings and history consistent.
* Pixel-diff baselines (KI-26) remain open.

Evidence: `docs/design/evidence/1.4/` (55 screenshots at 1440×900, 1024×768, 390×844, synthetic data created through the API).
