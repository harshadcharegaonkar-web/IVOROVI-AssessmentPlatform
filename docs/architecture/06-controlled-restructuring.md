# Controlled restructuring of the organization and academic structure (future design)

* Status: **Design for a later increment** (product-owner request with the 1.4 acceptance). Not implemented.
* Current behaviour (accepted in 1.4, unchanged): organization units that own academic records cannot be moved or
  deleted (`org_unit_has_academic_records`, `org_unit_has_dependents`); programs, courses, subjects and batches cannot
  change their owner, program or course after creation (KI-42, KI-43).

## Why a dedicated mechanism

Real institutions do restructure: a department moves to another campus, a college is affiliated to another university,
two departments merge, a course moves to another department. A plain "move" would silently break rules that other
records rely on (a course's department must lie inside its program's institution; faculty must belong to the
institution; read and manage scopes follow the hierarchy). Restructuring must therefore be an explicit, reviewed,
audited operation.

## Proposed design

### 1. Restructuring request (two-person rule)

* New entity `restructure_request`: type (`MOVE_UNIT`, `MERGE_UNITS`, `REASSIGN_COURSE_DEPARTMENT`,
  `TRANSFER_PROGRAM_OWNER`, `REASSIGN_SUBJECT_OWNER`), source, target, effective date, reason, status
  `DRAFT → VALIDATED → APPROVED → APPLIED` (or `REJECTED`, `CANCELLED`), requested by, approved by.
* New permission `org.restructure` (MANAGE, organization-only). The requester must hold it and `org_unit.manage` at
  **both** source and target; a second holder must approve; nobody approves their own request.

### 2. Validation (dry run)

A dry run computes the full impact before anything changes, and the request cannot be approved while blocking issues
remain:

* hierarchy rules (allowed parents, no cycles, active targets);
* academic invariants after the change (course departments inside the program institution; subjects reachable by every
  curriculum that uses them; academic years still applicable to every batch);
* staff membership: faculty assignments whose staff would fall outside the institution (blocking for open
  assignments, informational for history);
* access impact: role assignments and custom roles scoped to moved units, and users who gain or lose visibility
  (listed, never silently changed);
* open activities: active terms, open offerings, and later assessments/examinations in progress (blocking by default).

The dry-run report is stored with the request and shown to the approver.

### 3. Application

* Applied in one transaction with row locks on every affected record; the hierarchy paths are rewritten as today's
  move does; caches (principal cache, scope paths, academic generation) are invalidated after commit.
* Records keep their identifiers; history is never rewritten. Where ownership changes, the record gets an
  `ownership_history` row (from, to, request id, effective date) so reports can show who owned what and when.
* Merges deactivate the source unit (never delete it) and point it to its successor.

### 4. Audit and evidence

* Audit events `restructure.requested`, `.validated`, `.approved`, `.rejected`, `.applied` with the dry-run summary,
  and one event per changed record referencing the request id.
* The request, its dry-run report and the applied change set are retained with the audit trail.

### 5. Out of scope until then

Until this mechanism ships, legitimate changes are handled by creating new records (new course, new batch) and
deactivating the old ones, which keeps every historical record intact.
