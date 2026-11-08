# ADR-0009 — Question bank: versioning, governance and answer-key isolation

Status: accepted for Increment 2.1 (plan approved 2026-11-08; P2-07, P2-08). Date: 2026-11-08.

## Context

Questions are reusable, institution-owned examination material. Assessments (from 2.2), examination attempts and
published results must keep pointing at exactly the content that was approved. Review and approval must be
independent: standard banks need at least two people; high-stakes and admission banks need three. Institutions may be
stricter, never weaker. Answer keys are the most sensitive part of a question and must not leak through any other
path: candidate APIs, search, exports, logs, caches or errors. Authority over keys must not come from administrative
power alone.

## Decision

1. **Immutable versions.** A question is a sequence of `question_version` rows. Only the open version (DRAFT,
   IN_REVIEW, REVIEWED) can change.
   - APPROVED, SUPERSEDED and RETIRED versions are immutable. The database trigger `question_version_guard` compares
     every column except the forward state change, retirement and bookkeeping (an allowlist, so future columns are
     protected by default).
   - Editing an approved question creates a new draft version; the approved version stays in use until the new one is
     approved, and then becomes SUPERSEDED.
   - References (`question_version_reference`, module API `QuestionReferences`) may point only at approved,
     superseded or retired versions. They are permanent. A referenced version, or one ever submitted, cannot be
     deleted.
2. **Review bound to content.** The review hash covers content, options, marking, marks, taxonomy, image alt texts,
   the explanation's hash and the key's keyed hash.
   - A review counts only while its `reviewed_hash` equals the version's current hash.
   - Any modification of reviewed content returns the version to DRAFT and invalidates its reviews.
   - The database refuses a content change in review without that return, and refuses reviews whose hash does not
     match.
3. **Policies.** A bank's own policy is combined with institution floors along the unit hierarchy, taking the strictest
   (`question_effective_policy`).
   - STANDARD: author and approver are different people, and the author never reviews or approves their own question.
   - HIGH_STAKES (always for admission examination banks): author, reviewer and approver are three distinct people.
   - Enforced in the service and again in the database trigger at the moment of approval (valid review count, author
     never approves, distinct approver).
   - Weakening a bank policy or a floor goes through the four-eyes framework (ADR-0007).
4. **Answer keys in a separate store.** Keys and explanations live only in `question_answer_key`, encrypted per field
   (`FieldCipher`), with a keyed hash for review binding.
   - Only `AnswerKeyStore` reads that table; a test enforces this.
   - Visibility by version state:
     - DRAFT: the author only;
     - IN_REVIEW / REVIEWED: the author, plus holders of review or approve at the bank;
     - APPROVED and later: holders of `question.key.read` or `question.approve`.
   - `question.bank.manage` never grants key access.
   - Keys appear only in the dedicated endpoint, with `Cache-Control: no-store`. Every view and every refusal is
     audited. Keys are not indexed for search, not in imports' row results, and not in errors or logs.
   - API clients can hold no question scope at all.
5. **Privileged permissions (P2-08).** `question.approve` and `question.key.read` are privileged.
   - Administrators hold read, author, review and bank management, so the 1.3 ceiling still lets them assign FACULTY
     and ASSESSMENT_DESIGNER.
   - A role containing a privileged permission is assigned only through a four-eyes request. It is approved by a
     current holder of all those permissions at the scope (never the requester or the target), with live checks at
     approval and at activation. Failed re-checks invalidate the approval.
   - The database (`role_assignment_privileged_guard`) refuses privileged assignments by the application role without
     a matching approved request.
   - The first holder is bootstrapped by a platform operator with a ticket reference, only when no holder exists
     (runbook `privileged-role-bootstrap.md`).

## Consequences

- Historical assessments and results can rely on version ids; retiring or superseding a question never changes them.
- More tables and triggers than a simple CRUD design. Migrations that legitimately change question data must account
  for the guards (the schema owner is exempt only from the role-assignment guards).
- Organizations without an Exam Controller need the operator bootstrap once.
- The DB-level policy check duplicates the service rules on purpose; tests exercise both (`QuestionLifecycleIT`,
  `PrivilegedRoleAssignmentIT`).
