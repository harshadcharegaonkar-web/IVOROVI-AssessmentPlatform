# Increment 1.1: organization hierarchy

* Status: **In progress** (authorized 2026-11-02)
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
