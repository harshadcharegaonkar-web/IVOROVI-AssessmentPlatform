# Increment 1.3: custom roles and permission management

* Status: **Implemented and verified; awaiting product-owner review** (authorized 2026-11-03 with the 1.2 acceptance). Increment 1.4 not started.
* Depends on: 1.2 (accepted; closing security verification recorded in `increment-1.2.md`).

## Scope delivered

| Area | Delivered |
|---|---|
| Permission catalogue | 30 permissions in 14 functional domains (`docs/security/permission-catalogue.md`), each with stable code, display name, description, domain, level (read / write / manage), scope rule (organization-only or unit) and availability. 13 are available, 16 planned for later modules (listed, never grantable), 1 platform-only (hidden from organizations). Served by `GET /api/v1/permissions` |
| Roles | System roles (immutable) and custom roles: create, edit, view, clone (from system or custom roles), activate / deactivate with reason, archive (the "delete": only without active assignments), search and filter by type and status, sort, pagination; users assigned to a role; permissions and assignment scopes of a role |
| Role scope | A custom role is either organization-wide or limited to one unit's subtree (`scopeOrgUnitId`); limited roles can only be assigned there and below and cannot contain organization-only permissions; organization-only custom roles can only be assigned for the whole organization |
| Ceiling | Every permission put into a role on create, clone, edit or reactivation must be held by the editor at the role's scope; unknown, planned and platform codes are refused; reserved codes (any system role code, `PLATFORM*`) are refused, so a custom role can never become a platform super-admin or student role |
| Assignment | Custom roles join the existing workflow (organization, university, college, campus, department; optional start/end dates; revoke with reason); inactive or archived roles cannot be assigned; existing anti-escalation, no-self-assignment and last-organization-administrator rules unchanged |
| Deactivation | Deactivating a role suspends every assignment of it **on the next request** (the principal resolver ignores inactive roles and the organization's cached principals are invalidated); assignments are kept, the access overview shows the role as inactive, and reactivation restores them. Archive is refused while assignments are active (`409 role_in_use`: revoke/reassign or deactivate) |
| Unit administrators | INSTITUTION_ADMIN gains `role.manage` (migration), so a college administrator can define roles for their college — never organization-wide roles, never beyond their own permissions |
| Screens | Role listing; role details (Permissions, Assigned users, Overview); create / edit / clone with a grouped permission configurator, level badges, disabled entries with reasons, planned modules collapsed, and an **Effective access** summary with warnings and the list of changes; permission catalogue; role comparison (up to four roles, differences highlighted, "only differences"); role assignment overview; deactivation and archive confirmations; Access navigation group |

## Rules and safeguards (all enforced on the server)

| Rule | Where | Test |
|---|---|---|
| Only `role.manage` at the role's scope may create/edit/clone/deactivate/archive | `RoleService.requireManage` | `CeilingAndScope`, smoke |
| Ceiling: permissions held by the editor at that scope | `RoleService.validatePermissions` | `aCollegeAdministratorCannot…` |
| System roles immutable | `RoleService.requireCustom`, DB check `role_system_fixed` | `systemRolesAreProtected`, smoke |
| No platform permission in custom roles | service + DB check `role_custom_not_platform` | `unknownPlannedPlatform…` |
| Unit-limited / organization-only assignment rules | `AssignmentService.assignRole` | `rolesAreAssignableAtEveryLevel…` |
| Inactive / archived roles grant nothing | `DatabasePrincipalResolver` | `deactivationSuspendsAssignments…`, E2E |
| Cache invalidation on any role change | `PrincipalCache.evictTenant` after commit | `editingARoleChangesHoldersAccess…` |
| Concurrency: optimistic version on edit; unique code; role row locks serialise archive/deactivate with assignment | `RoleService.lock`, `AssignmentService` `FOR SHARE` | `Concurrency.concurrentEditsCreatesAndArchiveVersusAssign` (5 rounds) |
| Audit: `role.created`, `role.cloned`, `role.updated` (permissions added/removed), `role.activated`, `role.deactivated` (affected assignments, reason), `role.archived`, `role.assigned`, `role.revoked`, `role.change_denied` (unknown/not-grantable codes, ceiling, system role, scope) | `RoleService`, `AssignmentService` | assertions in each group |

## Database (`V202611031000__identity_custom_roles.sql`)

`role` gains `status`, `scope_org_unit_id` (FK to the tenant's org unit), `cloned_from`, `archived_at`, `created_by`, `updated_by`, `status_reason`, a check that system roles stay unscoped/active/unarchived, and a check that custom roles never contain `platform.tenant.manage`. INSTITUTION_ADMIN gains `role.manage`. Org units count roles limited to them as dependents.

**Upgrade of the development database** (1.2 data): 15 system roles active, 612 role assignments and 623 accounts preserved; schema at `202611031000`.

## APIs (`/api/v1`)

| Method | Path | Permission |
|---|---|---|
| GET | `/permissions` | `role.read` |
| GET / POST | `/roles?q=&type=system\|custom&status=&page=&size=&sort=&direction=` / `/roles` (Idempotency-Key; `clonedFromRoleId` to clone) | `role.read` / `role.manage` at scope |
| GET / PATCH / DELETE | `/roles/{id}` (PATCH: version; DELETE: `?version=` archives) | `role.read` / `role.manage` |
| POST | `/roles/{id}/status` `{status, version, reason}` | `role.manage` |
| GET | `/roles/{id}/holders?includeInactive=` | `role.read` (visible users only) |
| GET | `/roles/assignable` | `role.read` (replaces the 1.2 `GET /roles` list used by the assign dialog) |
| GET | `/role-assignments?q=&roleId=&orgUnitId=` | `role.read` (visible users only) |

## Results

| Check | Result |
|---|---|
| Backend (JUnit + Testcontainers) | **152 tests**, 0 failed — incl. `RoleManagementIT` (11 tests incl. concurrency), `UserManagementIT` (22 incl. the 1.2 security verification), all 1.0–1.2 suites |
| Smoke test with real Keycloak tokens | **43 passed** (adds catalogue, custom role create, faculty and college-admin denials, system role edit refused, archive, provisioner least-privilege probe) |
| Frontend unit tests | **39 passed** |
| Typecheck / lint | clean |
| Bundle | initial **176.1 kB gzip** (≤ 180 kB; headroom 3.9 kB, KI-37); largest lazy chunk 34.7 kB |
| Playwright desktop / tablet / mobile | **68 passed** (1.0–1.2 suites unchanged and passing): list/filters/catalogue, create with configurator → edit → clone → compare, assign custom role → holder signs in with access → deactivate (access gone on next request) → reactivate → archive refused while assigned, college administrator (unit-only roles, organization-only permissions disabled, system role read-only, clone drops what cannot be granted), assignment overview, no horizontal scrolling on every new screen |
| Accessibility | axe WCAG 2.1 AA: no serious/critical violations on roles list, catalogue, editor, role detail, comparison, assignment overview |
| GitHub Actions | run 41578791577 on `ee21b06`: backend, frontend (incl. bundle budget), end-to-end (compose stack, real Keycloak, smoke, Playwright) and security scan all green |

Acceptance criteria 1–21: 1–3 `Lifecycle`, E2E; 4–5 `CeilingAndScope`; 6 `systemRolesAreProtected`; 7 self-edit/self-assign in `CeilingAndScope`; 8 assignments at all five levels; 9 revocation in `editingARole…`; 10 deactivation; 11 cache (edit, deactivate, revoke take effect next request); 12 last organization administrator: ORG_ADMIN cannot be edited/deactivated and the 1.2 concurrency verification still passes; 13 audit; 14 `Isolation`; 15 migration test + dev upgrade; 16–21 suites, smoke, Playwright, axe, regressions, CI.

### Defects found during verification
* For a college administrator the role editor showed their college as scope while the form still held "whole organization", so organization-only permissions looked selectable (the server refused them). The form now defaults to the first unit they may use.
* `count(*) … FOR UPDATE` is invalid in PostgreSQL (archive returned 500) — replaced by a row lock on the role.
* The organization-only assignment rule initially applied to system roles too, which would have broken existing college-level INSTITUTION_ADMIN assignments; it now applies to custom roles, and the access overview no longer lists organization-only permissions for unit-level assignments (they were never effective there).
* `OutboxIT` assumed the outbox backlog fits in one publishing batch; with more suites it could miss its event. The test now drains until its own event is published (product behaviour unchanged).

### Not delivered / deviations
* Changing the scope of an existing custom role is not supported (clone it instead) to keep existing assignments valid.
* Custom roles cannot hold planned permissions until their modules ship.
* Pixel-diff baselines (KI-26) remain open.

Evidence: `docs/design/evidence/1.3/` (33 screenshots at 1440×900, 1024×768, 390×844).
