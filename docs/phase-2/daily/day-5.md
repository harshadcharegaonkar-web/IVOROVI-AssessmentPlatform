# Seven-day execution — Day 5 report (2026-11-15)

Scope was set by the Day 4 review:
- the target-concurrency load test is deferred under conditions (D-14);
- KI-89 is approved;
- KI-79 stays open until verified (D-16);
- KI-88 needs its root cause, with no retries or timeout changes;
- examination operations;
- a production dependency tracker.

Synthetic data only, real services (PostgreSQL, Keycloak, Valkey, RabbitMQ). **No production readiness is claimed.**

## Commits on `main` (normal pushes; no history rewritten; environment identity, signed — D-13)

| Commit | What |
|---|---|
| `f78aafb` | Decisions D-14 (load test deferred under conditions), D-15, D-16; load-test environment requirements for DevOps (`runbooks/load-test-environment.md`); production dependency tracker (`runbooks/production-dependencies.md`) |
| `14d0434` | **KI-89:** a durable, account-level check-in guard in PostgreSQL that works without Valkey |
| `3583f9b` | Examination operations (extra time, termination with four-eyes approval, connection interruptions, permission separation); **KI-92** attempt heartbeat; **KI-79** immediate revocation of pre-reset access tokens; **KI-88** failure-only diagnostics |
| (this commit) | This report; defect register updated with the CI evidence |

## CI evidence

| Run | Commit | Result |
|---|---|---|
| [43680555624](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43680555624) | `f78aafb` | All jobs green (documentation and decisions only) |
| [43684343079](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43684343079) | `3583f9b` | **All jobs green, first attempt, no test retried, none flaky.** Backend, frontend and security scan green. Staff E2E 119 passed; exam E2E 24 passed and 1 skipped (the opt-in crash test). Passed: the new KI-79 test and the Day 5 operations journey; the existing KI-79 password-reset test (`candidate.spec.ts`, third consecutive first-attempt pass); and the KI-88 mobile students test (diagnostics not triggered) |

## Test counts (this commit set)

| Suite | Result |
|---|---|
| Backend, full suite (local, before push) | **489 passed, 0 failed, 0 skipped** (99 classes); Day 4 ended at 477 |
| New backend tests | `CheckInDurableGuardIT` 4, `ExamOperationsIT` 5, `PasswordResetSessionsIT` 3 (+12 incl. updated `AttemptIT`, `PrivilegedRoleAssignmentIT`) |
| Frontend unit (vitest) | 92 passed (web 39, exam 8, content 31, ui 11, api-client 3); typecheck and lint clean |
| Exam E2E (local, real Keycloak, `--retries=0`) | exam-journey, attempt, attempt-edge 15/15; candidate and exam-journey 10/10; the new `password-reset-sessions.spec.ts` 2/2 (`--repeat-each=2`) |
| Staff E2E students mobile (KI-88), behind production nginx | 2/2 with the new diagnostics |

No test was retried, skipped or given a longer timeout to pass.

## KI-89 — account-level check-in protection that survives a Valkey outage

**Built.**
- New table `check_in_guard`, one row per candidate account. It has row-level security, and it does **not** store the session code.
- Wrong codes are counted by one atomic upsert per failure.
- After 15 failures within 15 minutes (configurable), check-in is refused for a cooldown with 429 `check_in_cooldown`:
  - the cooldown is 15 minutes, doubling per repeat, capped at 2 hours (all configurable);
  - there is **never a permanent lockout**: the block always expires;
  - a successful check-in deletes the row.
- Failures during an active cooldown are not counted. Without this, a burst of concurrent guesses started several cooldowns at once. The test found this defect, and it was fixed before commit.
- The guard is checked **before** the Valkey limiter, so it holds while Valkey is down.
- The existing layers stay in place: the code, device and address limits in Valkey, and the per-code lock.
- Audit: `exam.check_in.cooldown_started` records counts only, never the code.

**Tested** (`CheckInDurableGuardIT`; the Valkey outage is simulated with a limiter that reports down):

| Requirement | Test |
|---|---|
| Normal operation, recovery, capped escalation, no effect on other candidates | `aCooldownAfterRepeatedFailuresThenRecoveryAndCappedEscalationWithoutLockingOutOthers` |
| Valkey unavailable | `theGuardHoldsWhileValkeyIsDown` |
| Concurrent guessing (parallel wrong codes) counted atomically, exactly one cooldown | `concurrentGuessesAreCountedAtomically` |
| A legitimate candidate below the threshold checks in with the right code and the counter clears | `aLegitimateCandidateBelowTheThresholdRecoversWithTheRightCode` |

Session-code security and candidate authentication rules are unchanged. KI-89 is fixed and CI-verified (run 43684343079).

## KI-79 — password reset with several active sessions (stays open, D-16)

**The two Day 4 flaws:**
1. The guard skipped the forced sign-out whenever it saw *any* LOGOUT between the reset request and the new password. An unrelated logout was enough to leave other devices signed in.
2. A reset was marked handled *before* its sign-out ran, so a failed call to Keycloak was never retried.

Both were fixed on Day 4 and are covered by `ExamAdminIT`, which simulates an unreachable identity provider and then a success.

**Day 5: a third gap was found and fixed.**
- **The gap:** Keycloak ends the *sessions*, but an access token already issued to an ended session was still accepted by the delivery API for up to its 5-minute lifetime.
- **The fix:** the guard now records the reset on the candidate account (`sessions_revoked_before`, `kept_session_id`; migration `V202611170030`).
- **The effect:** the delivery API refuses with 401 `session_ended` every token issued before the reset, except the reset's own session.

**Evidence:**

| Required scenario | Backend (`PasswordResetSessionsIT`) | Real Keycloak E2E (`password-reset-sessions.spec.ts`) |
|---|---|---|
| Reset with multiple active sessions | Several sessions end at once | Laptop, tablet and a busy session: refresh refused (400) and API refused (401) for each |
| Sessions created after the reset are not revoked | Kept and later sessions keep working | The reset's own browser session (single sign-on, no form) and a phone signed in after the reset both work after another guard run |
| Revoked sessions are actually unusable | 401 `session_ended` on unexpired tokens | Same, against the live delivery process |
| Repeated resets | Second reset ends the first reset's session | Second reset ends the post-reset phone; a sign-in after it works |
| Concurrent session activity | Requests running during the guard never let an old token through afterwards | A session refreshing and calling the API every second throughout gets **no 5xx** |
| Second-resolution boundary | `theRevocationRuleAtTheSecondBoundary` | — |

**Status: open.** The root cause of the original CI failure (run 43607780575) is still not proven. Per D-16, KI-79 closes only after repeated first-attempt CI passes of both the existing and the new E2E test, not because of this run.

## KI-88 — mobile student-screen E2E in CI: investigation

**What was measured.**
- The setup was the production nginx build with Pixel 7 emulation. The full backend suite ran at the same time, so the machine was CPU-loaded (load average 3.7–4.5).
- I timed every one of the test's nine full page loads, in three runs.
- The heading appeared in **199–794 ms**:
  - callback at +56 to +615 ms;
  - token at +109 to +739 ms.
- The test's limit is 5 s. **No reproduction.**

**Conclusions so far:**
- CPU starvation alone does not explain a stall of more than 5 s. That points away from the app's own rendering, but the hypothesis is not proven.
- The Day 4 hypothesis (KI-86 query plans) was already rejected.
- **The evidence does not yet distinguish test infrastructure from product behavior.** I will not claim either.

**Change made, with no retry, timeout or assertion change.**
- The test now records each request of every page load: start offset, status and duration, plus failed requests.
- Only on a failure, it prints that timeline, the final URL and the visible text to the CI log, then fails exactly as before.
- The next CI failure will show where the time goes:
  - in Keycloak (authorize or token);
  - in the API;
  - in a chunk download;
  - or in the app itself (nothing outstanding).

**Status: open.** Root cause unknown.

## Examination operations (R-2 proposed)

| Capability | Implemented | Tested | Production-authorized |
|---|---|---|---|
| Attendance and check-in (who checked in, started, submitted) | Yes (Day 4 monitor) | `InvigilationIT`, E2E | No |
| Live status (writing, submitted, progress, time left) | Yes | `InvigilationIT`, E2E | No |
| Connection interruption monitoring — **KI-92 fixed**: the exam app now sends a heartbeat every 15 s while writing; the monitor shows the number of interruptions and the total seconds | Yes | `ExamOperationsIT.connectionInterruptions…`, E2E (shown online while writing) | No |
| Incident recording and audit | Yes (Day 4) | `InvigilationIT` | No |
| Authorized extra time: `exam.schedule.manage`, 1–60 min, reason, never beyond the window, append-only, the database lets the deadline move only forward and only by recorded minutes; the candidate sees it within one heartbeat | Yes | `ExamOperationsIT.extraTime…`, E2E | No |
| Termination: requested by an invigilator, decided by a **different** holder of the new **privileged** `exam.attempt.terminate`; enforced in the approval handler and by a database CHECK; answers kept and scored; result still under R-1 | Yes | `ExamOperationsIT.termination…`, `.aRejectedTermination…`, E2E (self-approval refused) | No |
| Permission separation: invigilator, controller, reviewer, approver | Yes | `ExamOperationsIT.invigilatorControllerReviewerAndApproverPermissionsStaySeparate`, `AttemptIT` (R-1) | No |
| Answer persistence, recovery, submission, result governance (regression) | Yes | `AttemptIT`, `DeliveryIT` 14, attempt E2E, full suite | No |

**R-2 needs your confirmation.** It is recorded as *Proposed*. The open question is whether extra time during an
examination should also need a second person. My recommendation is no: the reason is mandatory, every grant is audited
and append-only, and a 1–60-minute cap applies. A second approval while the candidate's clock runs risks the extension
arriving too late.

### Invigilator workflow demonstration

These screenshots come from the real E2E run (a temporary copy of the Day 5 test with screenshot calls, deleted after
the run): the staff app on 5173, the exam app on 5174, real Keycloak, and a synthetic candidate. They are shown on the
report page.

1. The controller's live monitor shows the candidate *Writing*, *Online* and 1 of 3 answered.
2. "Extend time" opens a dialog asking for minutes (1–60) and a reason.
3. The monitor then shows "+5 min extra".
4. The candidate's screen shows "Your time was extended by 5 minutes."
5. The head of institution (who invigilates) uses "End examination" and gives a reason.
6. The monitor shows "Ending requested". The head's own approval is refused.
7. The exam controller approves. The candidate sees "Your examination was ended by the examination staff. Your saved answers were kept."

## Production dependency tracker

`docs/runbooks/production-dependencies.md` holds the full tracker. External owners are roles; **the product owner names
the person or vendor for each**. The dates are proposals for confirmation.

| # | Dependency | External owner (role) | Proposed target | Status |
|---|---|---|---|---|
| P-1 | ClamAV in staging and production, real malware validation | DevOps lead | 2026-11-24 | Open — not provisioned |
| P-2 | Encryption-key custody, backup, recovery (rotation tool: engineering, ~1 day) | DevOps lead + security officer | 2026-11-27 custody; 2026-12-04 rotation | Open |
| P-3 | External penetration test and remediation | Security vendor (contracted by product owner) | Objective path 2026-11-30, fixes by 2026-12-04 | Open — vendor not engaged |
| P-4 | DPDP legal review | Legal counsel | Candidate notices 2026-12-04 | Open — counsel not engaged |
| P-5 | Real-device Safari | QA with devices | 2026-11-27 | Open |
| P-6 | Screen-reader testing | Accessibility tester | 2026-11-27 | Open |
| P-7 | Production-like load-test infrastructure (D-14; requirements in `load-test-environment.md`) | DevOps lead | Environment 2026-11-25 → test by 2026-11-27 | Open — requested 2026-11-15 |

**Load test (D-14).**
- The requirements document records:
  - target concurrency: S1 2,000 per event; S2 10,000 per region; S3 a burst of 10,000 submissions; S4 chaos; S5 shared address;
  - thresholds (p95 save < 300 ms, p99 < 1 s, zero lost acknowledged answers), fixed before execution;
  - the CPU, memory, database, Valkey and network configuration.
- The test runs within two working days of hand-over.
- The Day 4 single-machine run (one shared 4-vCPU container) is **not** production-scale validation.

## Defect register (Day 5 changes)

| Issue | Severity | Status |
|---|---|---|
| KI-89 code guessing bounded only by the code space during a Valkey outage | Low | **Fixed, CI-verified** (run 43684343079) |
| KI-92 no heartbeat while writing (monitor showed writers offline; interruptions not recorded) | Medium | **New, fixed, CI-verified** (run 43684343079) |
| KI-79 password reset and other sessions | Medium | Two Day 4 flaws fixed; a third (unexpired tokens) fixed Day 5. Both E2E tests passed in run 43684343079 (the existing one for the third consecutive run). **Open** until repeated CI passes (D-16) |
| KI-88 mobile students E2E in CI | Low | **Open**; no local reproduction under load; diagnostics added; passed in run 43684343079 (one pass proves nothing about the cause) |
| KI-82 scheduling reads other modules' tables | Low | Deferred (documented) |
| KI-91, KI-66, KI-86, KI-90, KI-75 | — | Fixed / resolved, CI-verified (Day 4) |

## Implemented vs tested vs production-authorized

- **Implemented and tested** (CI-verified, run `43684343079`):
  - objective authoring, scheduling and enrolment;
  - candidate accounts, check-in and delivery;
  - durable answers and idempotent submission, auto-submit;
  - scoring with R-1 review and approval;
  - invigilation with incidents, extra time, termination and interruption monitoring;
  - KI-89, KI-79 (Day 5 part), KI-92.
- **Tested only at small scale.** Load at target concurrency has not been tested (D-14, P-7). Delivery was tested with up to 1,000 candidates on one shared machine.
- **Production-authorized: nothing.** P-1 to P-7 are all open, and no real candidates may be examined.

## Remaining engineering effort (to Day 7 milestone)

| Item | Days |
|---|---|
| Day 6: KI-79 / KI-88 CI follow-up; R-2 changes if you decide differently; proctor self-service entry point; live disconnect alerts on the monitor | 1 |
| Day 7: integrated demonstration of journeys 1–4 on one build, final regression, documentation | 1 |
| Load test at target concurrency (after P-7 hand-over) | 1.5 (outside Day 7, D-14) |
| Production-template Keycloak realm without dev client (SA-03), unprivileged web image (SA-04) — support for P-3 | 0.5 |
| RB-3 key rotation tool — support for P-2 | 1 |
| **Total** | **≈ 5 (2 within Day 7)** |

## Day 7 feasibility

**The Day 7 objective-examination milestone is achievable on synthetic data.**
- The remaining Day 6–7 items fit in about 2 days.
- The load test at target concurrency is outside Day 7 by decision D-14. It is scheduled within two working days of the infrastructure (proposed 2026-11-27).
- **Risk:** KI-79 and KI-88 depend on CI evidence. If either fails again, I report it as a failure with the new diagnostics. They are not hidden by retries.

## Revised full-platform delivery forecast

The baseline (`delivery-baseline.md`) is unchanged in scope: about 72 engineering days, with nothing removed.

**Day 7 path:**
- M3 delivery and M3b invigilation reached their scope on Day 5, as planned.
- The extra Day 5 work (KI-89, KI-92, KI-79 token revocation) came from the reserve for delivery hardening, not from other modules.

| Milestone | Baseline | Forecast | Condition |
|---|---|---|---|
| Day 7 objective-examination milestone (synthetic data) | 2026-11-15 (Day 7 of the programme calendar) | **Programme Day 7** (2026-11-17 in real time: Days 3–4 ran on one real day, Day 5 on 2026-11-15) | CI green on the Day 7 build |
| Load test at target concurrency | — | 2 working days after P-7 (proposed 2026-11-27) | DevOps provides the environment |
| Conditional real-candidate pilot | by 2026-12-06 | **2026-12-06, at risk** | P-1, P-2, P-3 (objective path), P-4 (notices), P-5, P-6, P-7. None has an owner named yet; any slip moves the pilot by the same amount |
| Full functional scope | 2026-12-20 | **2026-12-20 for M1–M9 and M13–M17; M10, M11 and M12 move day-for-day with consent, KVM nodes and the AI provider** | Two additional sessions productive (currently standing by without approved work: this reduces parallel capacity and is the main schedule risk) |
| Production rollout | 2026-12-28 to 2027-01-17 | Unchanged | Full pen test and retest, DR drill, RB-1 to RB-6 closed with evidence |

**Honest note on capacity.** The 12-20 date assumed three productive sessions. Sessions 2 and 3 have been standing by
since Day 3 because no additional usage was approved. If that continues, about 50 of the 72 one-agent days remain after Day 7.
With one session that is about 10 working weeks from 2026-11-18, which is **late January 2027 rather than 2026-12-20**. I need your decision on parallel
capacity before Day 7 to hold the December date.
