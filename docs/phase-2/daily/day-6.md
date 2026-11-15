# Seven-day execution — Day 6 report (2026-11-15)

Scope was set by the Day 5 review:
- R-2 approved with controls;
- KI-79 and KI-88 stay open until their evidence is complete;
- verify CI for `a620be5`;
- the P-1 to P-7 register;
- parallel sessions (D-17);
- three forecasts;
- freeze of nonessential features.

Synthetic data only, real services. **No production readiness is claimed.**

## Day 5 closure: CI for `a620be5` was **not** green

Run [43686184149](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43686184149):
- backend, frontend and security scan: green;
- **End-to-end failed**: KI-88 recurred, and the mobile dashboard test failed once (it passed on CI's pre-existing single retry).

So **Day 5 is not closed**. The failure produced the KI-88 root cause (below).

## KI-88: root cause identified (stays open)

**What the failure-only diagnostics in the CI log show:**
- Sign-in completed normally: authorize 302, callback, token 200, all within about 100 ms.
- The page's first API call, `GET /api/v1/me`, was refused with **429 Too Many Requests** ("Retry after 1 seconds").
- The app then showed "We could not open your workspace".

The 429 comes from the staff API's per-user read limit: a token bucket of 300 reads per minute, refilled at 5 per second.

**Evidence that the cause is the test traffic, not product behaviour:**
- I recorded the limiter's Valkey calls during a full local run of the staff suite. The single administrator account that almost every test uses made **460 reads in a 60-second window** (1,315 reads in 278 s).
- Its bucket fell steadily to **118 of 300** by the end of the run. That is where the mobile project opens the student and dashboard screens.
- The failing CI run was 21 % faster than the local run (3.8 min against 4.8 min), which compresses the same reads into less time. The passing CI runs took 4.0–5.7 min.
- No person produces 460 reads a minute. The limiter worked as designed.

**Limit of the evidence.** The mobile dashboard failure in the same run has the same symptom, but that test has no
diagnostics. The CI trace cannot be downloaded from this environment, because the artifact storage host is not allowed.
That failure is consistent with the cause but not proven.

**Not changed:** the limit, retries and timeouts.

**Proposed fix, awaiting your approval.** The tablet and mobile layout projects would sign in with their own seeded
administrator account. Each account's traffic would then match what one person can generate, the limit stays enforced, and
a separate test asserts the 429 behaviour. I have not made this change, because it changes how the test traffic is
distributed against a security control.

KI-88 stays open until the change passes repeatedly in CI.

## R-2 implemented (approved controls)

| Control (Day 5 review) | Implementation | Test |
|---|---|---|
| Only designated controllers/invigilators | New **privileged** permission `exam.attempt.extend`, held by EXAM_CONTROLLER and EXAM_PROCTOR only. Administrators do not hold it automatically, and appointing a holder needs a second administrator's approval (P2-08) | `ExamOperationsIT`: institution administrator and faculty refused; proctor and controller allowed; role catalogue asserted |
| Mandatory reason | Required; refused when blank | `ExamOperationsIT` |
| At most 60 minutes per grant | API, service and database CHECK | `ExamOperationsIT` (0 and 61 refused) |
| Immutable audit record | Permanent `exam_attempt_extension` row (UPDATE, DELETE and TRUNCATE refused) plus audit `exam.attempt.extended` | Database update and delete refused; audit counted |
| Candidate, examination, actor, timestamp, previous and new expiry | Columns `schedule_id`, `candidate_id`, `granted_by`, `granted_at`, `previous_deadline`, `new_deadline`. A database guard requires the row to match the running attempt and its current expiry, and `new = previous + minutes`. The audit details carry the same fields, never the reason | History chain asserted; forged row refused; audit fields asserted |
| Every additional grant recorded independently | One row per grant, chained (each previous expiry = the earlier new expiry) | 3 grants asserted |
| No silent modification or deletion | Trigger and REVOKE | `ExamOperationsIT` |
| Candidate and staff see the revised expiry | Candidate: attempt heartbeat and timer, with the notice "Your time was extended by N minutes". Staff: the monitor shows "Ends <time>", "+N min extra", and the full grant history (who, when, reason, previous → new end time, approver) | `ExamOperationsIT`; exam E2E "Day 5 operations" (history dialog) |
| Institutions may configure stricter approval | Per examination: "Require a second person". Each grant then becomes a four-eyes request (30-minute expiry) decided by a different holder. The database refuses unapproved or self-approved grants. The rule can be tightened at any time and relaxed only before the examination opens | `ExamOperationsIT.anInstitutionCanRequireASecondPersonForExtraTimeOnAHighStakesExamination` |
| Not extended to termination or result publication | Unchanged: termination needs `exam.attempt.terminate` (four-eyes); publication needs `exam.results.approve` (R-1) | Asserted |

Migration `V202611180010__extra_time_controls.sql` backfills the earlier synthetic grants.

## KI-93 (QA-D6-1): termination not bound to its approval in the database (fixed)

**Found by** Session 3. Acting as the application role, a pending termination request could be set to APPROVED without an
approval, and an attempt could be set to TERMINATED with no approved request. The API path was correct; the database did
not enforce it.

**Fix** (migration `V202611180020`):
- a termination becomes APPROVED only with the matching APPROVED four-eyes request: same request, recorded decider, and the decider is not the requester;
- an attempt becomes TERMINATED only while such a request exists;
- the same binding now applies to second-person extra time.

The two tests Session 3 wrote for the finding are enabled and pass.

## Parallel sessions (D-17, recorded on `main` in `a89b893`)

| Session | Assignment | Status |
|---|---|---|
| Session 2 | RB-3/KI-49 encryption-key rotation tool (engineering share of P-2), branch `ws-b/rb3-key-rotation` | It asked for the authorization to be on record before touching security-critical code. Recorded as D-17; design answered (ciphertext format, scope). Work started |
| Session 3 | Independent security review and tests of the Day 5–6 work, with repeated KI-79 runs, branch `ws-c/day6-security` | Found **KI-93 (QA-D6-1, Medium)**: the database did not tie termination to its four-eyes approval. It delivered 10 adversarial tests (`ExamOperationsAdversarialIT`), which I fixed against and merged as a file under this environment's identity: its commit `a6f423e` was authored under the product owner's name, contrary to D-13, and I asked it to stop doing that. It then hit its usage limit; the remaining scope (repeated KI-79 runs, KI-89, the journey) was re-sent |

Merged so far: Session 3's adversarial tests (in `e272e8b`). Session 2: nothing yet. "Productive capacity" is judged by merged, CI-green work (D-17).

## Forecasts (requested separately)

The baseline is about 72 engineering days for the full scope, nothing removed. After Day 7, about 50 one-agent days remain.

| Scenario | Assumptions | Full functional scope | Production rollout |
|---|---|---|---|
| **A. One primary session** (guaranteed) | 5 working days a week from 2026-11-18; no parallel help | **About 2027-01-27** (10 working weeks) | **February 2027**, after the full penetration test round, the disaster-recovery drill and RB-1 to RB-6 |
| **B. Three productive parallel sessions** (not yet demonstrated) | Sessions 2 and 3 productive from 2026-11-18; about 20 % lost to integration and review | **2026-12-18 to 2027-01-08** (2026-12-20 only in the best case) | 2026-12-28 to 2027-01-17 (baseline), if the external items keep their dates |
| **C. External dependency completion** | All P-1 to P-7 owners named and starting 2026-11-17 | Pilot gates closed by 2026-12-05 → conditional pilot 2026-12-06. M10 (consent and model hosting), M11 (KVM nodes by 2026-11-23) and M12 (AI provider by week 4) finish late by the same amount as their dependency | Needs the full penetration test, the disaster-recovery drill and counsel's sign-off: earliest mid-January 2027 |

The binding constraint is whichever of A/B and C finishes later. As of today, **no external owner is named**.

## Day 6 test counts

| Suite | Result |
|---|---|
| Backend, full suite (local, before push) | **500 passed, 0 failed, 0 skipped** (100 classes); Day 5 ended at 489 |
| New or changed backend tests | `ExamOperationsIT` 6 (new second-person test, R-2 extension test rewritten); `ExamOperationsAdversarialIT` 10 (Session 3); `PrivilegedRoleAssignmentIT` (six privileged permissions) |
| Frontend unit | 92 passed; typecheck and lint clean |
| Exam E2E (local, real Keycloak, `--retries=0`) | Day 5 operations journey with the grant history; `exam-journey` and `password-reset-sessions` 5/5 |

**CI runs today** (the first two clean on the first attempt; the third has a KI-79 failure hidden by the retry):

| Run | Commit | Result |
|---|---|---|
| [43714769088](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43714769088) | `a89b893` (D-17, R-2 recorded) | All green: staff E2E 119, exam E2E 24 + 1 skipped |
| [43716330555](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43716330555) | `5d54225` (R-2, register, KI-88 findings) | All green: staff E2E 119, exam E2E 24 + 1 skipped |
| [43718157717](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43718157717) | `e272e8b` (KI-93) | Reported green, **but not clean**: staff E2E 119 passed; exam E2E 23 passed, 1 skipped, **1 flaky**. The new KI-79 test **failed on its first attempt** and passed only on CI's pre-existing single retry (see KI-79) |

**KI-79 failed again in CI.** In run 43718157717 the new `password-reset-sessions.spec.ts` failed on its first attempt:
- after the reset, Keycloak refused the laptop's and tablet's refresh tokens (400), so their sessions had ended;
- but for the whole 45 s, the delivery API still accepted their unexpired access tokens (200 instead of 401).

The test passed on CI's pre-existing single retry, so GitHub shows the run as green. **I do not count it as a pass.**

The delivery API reads the revocation record from the database on every request, with no cache. So the likely cause is
that the record (`sessions_revoked_before`, written by `RecoveryGuard` in the administrative process) was not written
within 45 s. A possible reason: Keycloak ended the sessions itself (its "sign out other devices" option during the reset)
before our guard handled the event. **Not proven:** CI keeps no API logs, and its trace cannot be downloaded here.

**Day 7 action, with no retry or timeout change:** dump the guard's per-run counts and the API and delivery logs to the CI
log whenever an E2E test fails, including failures a retry hides; then fix the identified cause.

Both tests passed on the first attempt in runs 43684343079, 43714769088 and 43716330555, and locally. KI-79 stays **open**
(D-16).

## Day 7

Planned:
- KI-88 fix (if you approve it) and repeated CI evidence;
- KI-79 repeated runs;
- merge Session 2 and Session 3 work after review;
- full candidate-to-result validation on one build;
- the final milestone report with release-candidate instructions.
