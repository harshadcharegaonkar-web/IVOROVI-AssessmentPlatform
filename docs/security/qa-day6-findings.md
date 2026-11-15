# QA Day 6: adversarial review of the Day 5 work (and R-2)

Independent QA (Session 3, decision D-7). Branch `ws-c/day6-security`, from `main` a620be5, merged with `main` 5d54225 (R-2).
Synthetic data only. No production code changed. Every finding has a test that asserts the secure behaviour. Each such
test was run enabled and failed on the current code, and is now `@Disabled` with its reference, to be enabled with the fix.

## Findings

| Id | Severity | Area | Finding | Test (disabled until fixed) |
|----|----------|------|---------|------------------------------|
| QA-D6-1 | Medium | Termination (V202611170020) | The database does not tie a termination to its four-eyes approval. As the application role, a pending `exam_attempt_termination` can be set to `APPROVED` with any `decided_by` other than the requester (no approval exists; the attempt keeps running), and an attempt can be set to `TERMINATED` with no approved request. The result path has the equivalent guard (a release needs a `PUBLISHED` review). | `ExamOperationsAdversarialIT.theApplicationRoleCannotMarkATerminationApprovedWithoutAnApproval`, `…CannotEndAnAttemptAsTerminatedWithoutAnApprovedRequest` |
| QA-D6-2 | Medium | Extra time, second person (V202611180010) | Same class as QA-D6-1. On an examination that requires a second person, the insert guard only checks that `approved_by` is set and differs from the granter, and that `approval_id` is set. It does not check that the approval exists, is `APPROVED`, concerns this attempt, and was decided by `approved_by`. As the application role, a grant with a random approval id is accepted and the deadline then moves. | `ExamOperationsAdversarialIT.theApplicationRoleCannotGrantSecondPersonExtraTimeWithAForgedApproval` |
| QA-D6-3 | Low | KI-79 `RecoveryGuard` | The guard overwrites `kept_session_id` with the session of whichever reset it handles last, but `sessions_revoked_before` only moves forward. Suppose the sign-out of an older reset A fails while a newer reset B succeeds. The retry of A then re-exempts A's session, so its tokens are accepted again even though B ended it. Bounded by the token lifetime (at most 5 minutes; the identity provider ended the session, so no refresh). | `PasswordResetAdversarialIT.aRetriedOlderResetDoesNotExemptItsSessionAfterANewerReset` |
| QA-D6-4 | Low | KI-79 storage | The revocation is stored per organization (`candidate_account` row), not per identity. A token of an ended session is accepted: (a) at an organization the candidate joins later through self-registration (the token enrols them there); (b) at an organization that was not ACTIVE or TRIAL at the time of the reset and is reactivated later. Bounded by the token lifetime. | `PasswordResetAdversarialIT.anEndedSessionCannotEnrolTheCandidateInAnotherOrganization`, `…anOrganizationReactivatedAfterTheResetStillRefusesTheEndedSession` |
| QA-D6-5 | Low | KI-89 `CheckInLimits` | The cooldown check is a read before the attempt, and the failure is counted after it. Simultaneous guesses of one account are therefore all evaluated: 47–48 of 48 against a threshold of 5, with Valkey down, when the guard is the only per-account layer. The bound is the number of requests in flight, once per cooldown. The 49-bit codes keep the practical risk low. | `CheckInGuardAdversarialIT.noMoreGuessesThanTheThresholdAreEvaluatedInABurst` |
| QA-D6-6 | Info | Extra time | The examination window is enforced only in the service, in both direct and approved grants. The database checks the record's consistency and the 1–60 minute range, but not `new_deadline <= closes_at`. An approved second-person grant re-checks against the `windowCloses` captured in the request payload, not the schedule's current value. | none (design note) |
| QA-D6-7 | Info | KI-79 boundary | The issue time is compared in whole seconds, by design, so the reset's own session is never refused. A token that an *old* session obtained in the same second as the reset, before it, is therefore accepted until it expires. | covered by `PasswordResetSessionsIT.theRevocationRuleAtTheSecondBoundary` (documented behaviour) |

Suggested fixes, for Session A to decide:
- **QA-D6-1:** a status change to `APPROVED` requires an `approval_request` with id = `approval_id`, status `APPROVED`, the same `decided_by`, type `exam.attempt.terminate` and subject = the attempt. `TERMINATED` on `exam_attempt` requires such an approved request. The approval row is already `APPROVED` when `onApproved` runs.
- **QA-D6-2:** the same check in `exam_attempt_extension_insert_guard`, for type `exam.attempt.extend`.
- **QA-D6-3:** update `kept_session_id` only when the reset is at or after the stored `sessions_revoked_before`.
- **QA-D6-4:** keep the revocation per subject, in a platform table read by `requireCandidate` and `me()`, or apply it in every organization whatever its status.
- **QA-D6-5:** count, or reserve, the failure before evaluating, for example an upsert in the same statement as the cooldown check.

## What was tested and passed

- **Termination (`ExamOperationsAdversarialIT`).** These cannot decide a termination: the requester, another college's controller, administrators, faculty, and another organization's controller. A controller cannot decide their own request. An approval after submission changes nothing. Replays, and 8 concurrent approvals, apply it exactly once. A tampered approval payload cannot redirect the termination to another attempt. As the application role, the request row cannot be edited, repointed or deleted, and cannot be self-decided.
- **Extra time under R-2, over the API.** These are refused: 0, −5, 61 and 1000 minutes; another college; another organization; a grant after submission. Administrators, faculty and the designer cannot grant; the invigilator can. The response shows the previous and new expiry, and the reason is never audited. 8 concurrent 60-minute grants with room for two give exactly two.
- **Extra time with a second person required.** None of the following can decide: the granter, another college, administrators, faculty, another organization. 8 concurrent approvals apply once. An approval after submission, or with no room left in the window, changes nothing.
- **Extra time as the application role.** These are refused: a deadline move without a record; a record of another attempt; a record that misdescribes the attempt; out-of-range minutes; changing, deleting or truncating records; anything after submission; relaxing the second-person rule after the examination opened; unapproved, self-approved or half-approved grants.
- **KI-79 (`PasswordResetAdversarialIT`).** All 13 candidate endpoints refuse an ended session with 401. A token without a session id is judged by its issue time, and look-alike session ids are not exempt. The revocation holds in every organization the candidate belongs to. Repeated guard runs neither repeat (one audit record) nor undo it. An identity-provider failure is enforced on the next run.
- **KI-89 (`CheckInGuardAdversarialIT`).** Valkey is really unreachable here (a closed port, fail-open asserted). Check-in keeps working. The cooldown holds, even for the valid code. Other candidates are unaffected, and so is the same identity in another organization. The cooldown expires and the candidate recovers. A burst produces one cooldown and never succeeds. No session code appears in the audit trail, the failure records or the captured logs.

## Real-stack runs (native APIs, compose infrastructure on fresh volumes, real Keycloak 26.8)

| Run | Result |
|-----|--------|
| `password-reset-sessions.spec.ts`, `--repeat-each=5 --retries=0` | **5 passed, 0 failed** (29–32 s each) |
| `candidate.spec.ts` "register, verify, check in once, enrol TOTP, recover…", `--repeat-each=5 --retries=0` | **5 passed, 0 failed** (1.5 min each) |
| `exam-journey.spec.ts`, `recovery.spec.ts` (`QA_FAILURE_TESTS=1`, delivery process killed with SIGKILL and restarted), `attempt.spec.ts`, `attempt-edge.spec.ts`, `--retries=0` | **16 passed, 0 failed** |

The last run covers the full journey: invitation, login, check-in, answers across a reload and offline, submission, score, review, independent approval and result. It also covers answers surviving a crashed delivery process, idempotent submission (a double click and a replay), automatic submission at time-out, and the Day 5 operations journey.

Backend: `ExamOperationsAdversarialIT` 15 (12 passed, 3 disabled), `ExamOperationsIT` 6/6, `PasswordResetAdversarialIT` 7 (4 passed, 3 disabled), `PasswordResetSessionsIT` 3/3, `CheckInGuardAdversarialIT` 4 (3 passed, 1 disabled), `CheckInDurableGuardIT` 4/4.
