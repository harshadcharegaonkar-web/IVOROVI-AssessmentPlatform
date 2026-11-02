# Increment 1.1: organization hierarchy

* Status: **Implemented and verified locally; awaiting product-owner review** (authorized 2026-11-02). Increment 1.2 not started.
* Depends on: increment 1.0 (signed off)

## Functionality

Organization (tenant) → **University** → **College** → **Campus** → **Department**. Levels are optional.

| Unit type | Allowed parent |
|---|---|
| UNIVERSITY | none (top level of the organization) |
| COLLEGE | none (stand-alone college) or UNIVERSITY |
| CAMPUS | UNIVERSITY or COLLEGE |
| DEPARTMENT | UNIVERSITY, COLLEGE or CAMPUS |

* **Create, view, edit, search and filter** units. Edit covers code, name, short name, contact attributes, and accent colour for universities/colleges. **Type is immutable.**
* **Codes are unique among siblings, case-insensitive.** Universities and stand-alone colleges are unique within the organization; campuses and departments are unique within their parent. Two colleges may each have a `CSE` department.
* **Status:** ACTIVE / INACTIVE.
  * A unit with active children cannot be deactivated.
  * A unit cannot be activated under an inactive parent.
  * An active unit cannot be moved under an inactive parent.
* **Move (re-parent):**
  * Type rules apply.
  * A unit cannot move under itself or its own descendants.
  * The parent must exist in the same tenant (row-level security + composite foreign key).
  * Paths of the whole subtree are rewritten in one transaction.
* **Delete** only for an unused unit: no children, no role assignments scoped to it, no feature overrides. Otherwise the API returns `409 org_unit_has_dependents` with the dependent counts, and the deactivation workflow applies.
* **History:** every change is written to `org_unit_history` (who, when, change type, before/after) and to the hash-chained audit log, and emits an outbox event.
* **Scoped authorization:** an `ORG_UNIT` role assignment covers that unit and its entire subtree (ltree). Creating needs `org_unit.manage` on the parent (tenant-wide for top-level units). Moving needs it on both the unit and the new parent.
* **Feature settings:**
  * Overrides apply to universities and colleges and are inherited down the tree.
  * A lower level can switch a feature off but never on when an ancestor has it off.
  * A change history is shown from audit events.
* **Terminology:** per-tenant display labels for the four unit types (e.g. "School" for COLLEGE) in System Settings. Stored types never change.
* **Legacy endpoints:** `/api/v1/institutions` is removed (D-P1-05). Consumer check: the only consumers were this repository's web app, smoke test and E2E tests, and all three move to `/api/v1/org-units` in this increment.

Deferred, to keep the increment focused (listed in the plan for later increments):
* **Assign administrators from the unit page:** needs the role-assignment and anti-escalation APIs of increment 1.4. The page will gain the action there.
* **Unit logo upload:** needs file upload, which comes with document handling (ADR-0006). Branding in 1.1 is the accent colour.

## Database (`V202611021100__tenancy_org_units.sql`)

* `ltree` extension (a trusted extension, created by the schema owner).
* `org_unit` (tenant RLS):
  * `parent_id`, `unit_type`, `code`, `name`, `short_name`, `status`, `path ltree`, `attributes jsonb`, `branding jsonb`, audit columns, `version`.
  * Composite FK to its parent within the same tenant.
  * Unique index on `(tenant_id, coalesce(parent_id, 0…), lower(code))`.
  * GiST index on `path`.
* **Migration of Phase 0 data:**
  * Every `institution` becomes a top-level COLLEGE with the **same UUID**.
  * `feature_flag_override.institution_id` → `org_unit_id`.
  * `role_assignment.scope_type` INSTITUTION → ORG_UNIT.
  * Permission keys `institution.*` → `org_unit.*` in role definitions.
  * `institution` is dropped.
* `org_unit_history` (tenant RLS; app role may only INSERT/SELECT).
* `tenant_settings` (tenant RLS): terminology labels and time zone.

## APIs (`/api/v1`)

| Method | Path | Permission |
|---|---|---|
| GET | `/org-units?type=&parentId=&status=&q=&page=&size=&sort=&direction=` | `org_unit.read` (results limited to visible subtrees) |
| GET | `/org-units/tree` | `org_unit.read` (visible units, flat with parent ids) |
| GET | `/org-units/summary` | `org_unit.read` (counts by type/status; departments and campuses per institution) |
| GET | `/org-units/{id}` | `org_unit.read` in scope; includes ancestors (breadcrumb), child counts, dependents |
| POST | `/org-units` (Idempotency-Key) | `org_unit.manage` on parent |
| PATCH | `/org-units/{id}` | `org_unit.manage` in scope (optimistic `version`) |
| POST | `/org-units/{id}/move` | `org_unit.manage` on unit and new parent |
| POST | `/org-units/{id}/deactivate`, `/activate` | `org_unit.manage` in scope |
| DELETE | `/org-units/{id}?version=` | `org_unit.manage` in scope; unused units only |
| GET | `/org-units/{id}/history` | `org_unit.read` in scope |
| GET / PUT | `/tenant/settings` | read: authenticated · write: `tenant.settings.manage` |
| GET | `/features…?orgUnitId=` | as before; `institutionId` renamed |

## Screens

Organization hierarchy (tree + detail panel); University, College, Campus and Department listings
(search, status and parent filters, sort, pagination); unit details (breadcrumb trail, overview, children,
features for universities/colleges, history); create/edit form; move, deactivate/activate and delete dialogs;
System Settings (terminology); dashboard organization KPIs (universities, colleges, campuses, departments) and
organization distribution.

## Acceptance criteria

1. Multiple universities; colleges under universities and stand-alone; campuses under colleges; departments under campuses, colleges or universities.
2. Invalid parent types, cycles, cross-tenant parents and duplicate sibling codes are rejected server-side.
3. Phase 0 data migrates with IDs, grants and feature overrides intact (migration test plus upgrade of the existing development database).
4. Scoped permissions follow the subtree; escalation attempts (acting outside one's subtree, creating top-level units without tenant-wide permission) are refused and audited.
5. Deactivate, activate, move and delete obey the lifecycle rules; every operation produces history, an audit entry and an outbox event; caches are invalidated.
6. Cross-tenant suite extended to every new endpoint and table.
7. All screens follow the approved design system at 1440/1024/390 px with no horizontal scrolling and a clean axe scan; Playwright covers the workflows.
8. Increment 1.0 functionality has no regressions; backend, frontend, E2E and GitHub CI are green.

## Results (2026-11-02)

| Check | Result |
|---|---|
| Backend tests (JUnit + Testcontainers, real PostgreSQL/Valkey/RabbitMQ) | **113 passed**, 0 failed (`./gradlew check`; includes `OrgUnitIT`, extended `TenantIsolationIT`, `AuthorizationIT`, `FeatureFlagIT`, `ErrorHandlingIT`, Modulith verification) |
| Migration test | `OrgUnitIT` migration case: Phase 0 institutions → top-level colleges with the same ids, grants and overrides |
| Upgrade of the existing dev database (Phase 0/1.0 data at V202611021000) | 12 institutions → 12 colleges (same ids, migration history rows); 600 INSTITUTION grants → 600 ORG_UNIT; 1 override preserved; `institution` dropped |
| Smoke test with real Keycloak tokens | **19 passed**, 0 failed (`scripts/smoke-test.sh`) |
| Frontend unit tests | **36 passed** (api-client 3, ui 11, web 22) |
| Typecheck / lint | clean |
| Bundle budget | initial **173.3 kB gzip** (152.0 kB Brotli, 564.3 kB raw) ≤ 180 kB; largest lazy chunk 34.1 kB ≤ 40 kB. Lower than 1.0 (175.8 kB) after marking `@ivorovi/ui` side-effect free, which keeps page-only Radix parts (Tabs) out of the initial payload |
| Playwright (real Keycloak + API + DB), desktop 1440, tablet 1024, mobile Pixel 7 | **34 passed**, 0 failed: 1.0 suite updated for org units (regression) + 1.1 suite (hierarchy, lifecycle, listings, scoped admin, System Settings, overflow at tablet/mobile on every new screen) |
| Accessibility | axe WCAG 2.1 AA: no serious/critical violations on dashboard, hierarchy, department listing, unit detail, System Settings |
| GitHub Actions | run 41478606816 on `344d9f8`: backend, frontend (incl. bundle budget), end-to-end (compose stack, Trivy image scans, smoke, Playwright) and security scan all green |

E2E workflows covered: create a stand-alone college (client validation, server-side duplicate code `au` → field
error), add campus and department via "Add sub-unit", breadcrumbs, edit, move with reason, history (created /
updated / moved), refused deactivation of a parent with active children, deactivate/activate a leaf, "Delete (in
use)" guard, delete bottom-up, audit filter + chain verification; listing search/status/parent filters, sort,
empty state; scoped administrator (Ines, AIT subtree): no foreign units in the tree, 403 → "Access denied" on
another college, no top-level creation, no System Settings; terminology change ("School") reflected in the
navigation and listing, then reverted. Test data is removed through the API before and after the suite.

### Defects found during verification
* **KI-28 (backend, fixed):** unknown JSON fields were ignored. Strict rejection with `400 unknown_field`.
* **KI-29 (frontend, fixed):** the create form reused its Idempotency-Key after a 4xx, so a corrected submission
  was rejected as "key used for a different request". The key now rotates once the server has answered.
* **KI-30 (accessibility, fixed):** breadcrumb links and the selected tree row's type label were below 4.5:1.

### Not delivered / deviations
* Pixel-diff visual baselines (KI-26) are **not** automated yet; re-planned for 1.2.
* Assigning administrators from a unit page (needs 1.4 APIs) and logo upload (needs ADR-0006 scanning) remain deferred, as stated above.
* Legacy `/api/v1/institutions` is removed (D-P1-05); the UI routes `/institutions…` are replaced by `/organization…`.

Evidence: `docs/design/evidence/1.1/` (desktop, tablet and mobile screenshots of every new screen).

## Final verification checks (acceptance review, 2026-11-02)

The product owner approved 1.1 subject to six checks. Each is an automated test in
`OrgUnitIT.AcceptanceChecks` (real PostgreSQL, Valkey and RabbitMQ via Testcontainers). All six pass;
no product change was required.

| # | Check | Test | What it proves |
|---|---|---|---|
| 1 | Moving a unit recalculates inherited permissions | `check1_moveRecalculatesInheritedPermissionsForTheWholeSubtree` | After moving a campus (with a department) from college A to B: A's administrator loses read and manage on the campus **and** its department; B's administrator and B-scoped faculty gain them; list visibility follows |
| 2 | Moving invalidates affected caches | `check2_moveEvictsCachedPathsOfEveryAffectedUnit` | The cached scope paths (`ivorovi:orgpath:t:{tenant}:{unit}`) of the moved unit and every descendant are evicted after commit; unaffected units keep theirs; reloaded paths contain the new ancestor. Principal and feature caches hold grants and overrides by unit id, which a move does not change |
| 3 | Cross-tenant moves are rejected | `check3_crossTenantMovesAreRejectedInBothDirections` | Own unit under another tenant's parent → 422 `invalid_parent`; another tenant's unit → 404 (RLS) for either side; data unchanged; the composite foreign key refuses a cross-tenant parent even for the schema owner |
| 4 | History and audit stay intact after move/deactivate | `check4_historyAndAuditRemainIntactAfterMoveAndDeactivation` | Earlier history rows and audit `seq`/`hash` values are byte-identical afterwards (new entries only appended); audit chain verification is valid; the application role is denied `UPDATE` on `org_unit_history` |
| 5 | Units with dependent records cannot be deleted | `check5_unitsWithAnyDependentCannotBeDeleted` | Children, scoped role assignments and feature overrides each block deletion (409 `org_unit_has_dependents`, row still present); deactivation remains available |
| 6 | The organization is the tenant boundary | `check6_theOrganizationIsTheTenantNotAUnitType` | `ORGANIZATION` is rejected as a unit type (400); the database check constraint allows only UNIVERSITY, COLLEGE, CAMPUS, DEPARTMENT; top-level units have no parent and belong to exactly one tenant |
