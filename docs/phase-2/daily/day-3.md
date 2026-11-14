# Seven-day execution — Day 3 report (2026-11-14)

Scope set by the Day 2 review (decisions D-7 to D-11): finish and verify the candidate examination journey, the result workflow with
independent approval, KI-83 (question images) and KI-84 (browser E2E). Synthetic data only; real Keycloak, PostgreSQL, Mailpit,
staff and delivery processes. **No production readiness is claimed.**

## Commits on `main` today (all normal pushes, no history rewritten)

| Commit | What |
|---|---|
| `9a2a6ae` | Decision register D-7 to D-11 (session roles, Day 3 priorities, open-issue handling) |
| `48ee4f8` | Result workflow: review → independent approval → publication → candidate view; KI-83 scheduling guard. **Broke `main`** (see below) |
| `735413a` | Fix for `main`: result approval is a privileged permission; candidate journey browser E2E; staff results screen; candidate result view |
| `ff4bfac` | KI-83: question images delivered to candidates (own open attempt, own paper, scanned clean); scheduling guard narrowed |
| `bf7be55` | Merge of Session 2's KI-75 benchmark reproduction (docs only) |
| `6569c32` | Merge of Session 3's QA branch (tests only): delivery security regression, concurrency, browser attempt and crash-recovery tests, KI-81 test fix |
| `50bb254` | KI-85 test asserts the fix; KI-86 recorded |
| `8364c27`, `86150a0` | Merge of Session 3's KI-80 root cause and regression test; my KI-80 fix (immediate cache writes) |
| `a7af180`, `8df2a03` | Merges of Session 3's image-endpoint and result-workflow security tests and browser edge cases (tests only) |
| `191c8b9` | First KI-87 attempt (controller only; insufficient); KI-79 reopened |
| `b7d5e2d` | KI-87 fix in both nginx proxies |
| `f848544`, `bd1c5fb`, `b51ea2e`, `5967a9c` | This report, KI-88, CI evidence |

**A mistake I made, in the open:** I pushed `48ee4f8` after running only the targeted tests. Exam controllers gained the
result permissions, which administrators do not hold, so the 1.3 privilege ceiling stopped administrators from assigning that role:
8 identity tests failed in CI run [43553831640](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43553831640).
`main` was red for about 25 minutes until `735413a`. The fix does not weaken the ceiling: reviewing results is an ordinary
permission (granted to administrators), approving publication is **privileged** (P2-08 four-eyes delegation), and the reviewer can
never approve. Since then the full backend suite runs before every push.

## CI evidence

| Run | Commit | Result |
|---|---|---|
| [43555703966](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43555703966) | `735413a` | **All jobs green**, including the compose-stack E2E with the new candidate journey |
| [43607780575](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43607780575) | `50bb254` | **Failed (E2E job only)**; backend, frontend and security green. (1) The KI-83 image E2E found a duplicated `nosniff` header behind the exam proxy: a real defect in my endpoint, now fixed (KI-87). (2) `candidate.spec.ts` (password reset, KI-79) failed once and passed on the workflow's pre-existing single retry: KI-79 is **reopened** |
| [43610232127](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43610232127) | `191c8b9` | **Failed (E2E only)**: my controller-only KI-87 fix did not work (duplicates come from the proxies) |
| [43612273755](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43612273755) | `b7d5e2d` | **First attempt failed (E2E only)**: KI-88, staff suite. The exam suite therefore did not run. **The failed job was re-run once** to get that evidence. Re-run result: **all jobs green, no test retried and none flaky**: staff E2E 119 passed; exam E2E 22 passed and 1 skipped (the opt-in crash test). This is the CI evidence for KI-80, KI-81, KI-83, KI-84, KI-85 and KI-87 |

## Candidate examination journey (D-9 items 1–12)

Every step runs in a real browser against the real stack in `frontend/apps/exam/e2e/exam-journey.spec.ts`:

| # | Step | Evidence |
|---|---|---|
| 1 | Start (invitation e-mail → registration with the invited address → e-mail verification → check-in code → start) | journey test; QA `attempt.spec.ts` |
| 2 | Question loading and navigation | journey test (question buttons, previous/next) |
| 3 | Answer selection and autosave | journey test |
| 4 | Server acknowledgement after durable persistence ("All answers saved" only after the server stored the answer) | journey test; `AttemptIT`; QA concurrency IT (highest sequence kept, every accepted save journaled) |
| 5 | Page reload and session recovery (single sign-on, answers restored) | journey test; QA `attempt.spec.ts` |
| 6 | Network interruption and reconciliation (`context.setOffline`) | journey test; QA `attempt.spec.ts` (submission held back while unsaved) |
| 7 | Server-authoritative timer | journey test (≤ assessment duration); QA `attempt.spec.ts` countdown |
| 8 | Automatic submission at expiry (browser closed) | journey test 3 (AUTO_SUBMITTED, scored) |
| 9 | Candidate submission | journey test |
| 10 | Idempotent and duplicate submission; late write refused (409) | journey test; QA double-click and replay; QA concurrent submit IT |
| 11 | Objective evaluation | journey test: 2 + (−0.5) + 2 = **3.5 / 6**, as expected from the answers |
| 12 | Provisional result → review → refused self-approval → independent approval → publication → candidate view | journey test: candidate sees 409 before publication and "3.5 / 6" after, no explanations shown |

Extra delivery evidence from Session 3 (merged): answers survive a **crash of the delivery process** (SIGKILL), and the pending
answer is saved after the restart (`recovery.spec.ts`, opt-in because it kills a process).

**Local runs, no retries:** exam journey 3/3; QA attempt 5/5; QA edge cases 6/6; recovery 1/1. Of 17 exam E2E tests, 16 pass locally. The one failure
is `headers.spec.ts`, which needs the production container (vite dev does not send the production headers). CI checks it against the container.

## Result workflow

- **Provisional score:** automatic, server side, after submission or auto-submission.
- **Review by an authorised person:** `POST /api/v1/exam/results/{schedule}/reviews` hashes the provisional scores and opens a
  four-eyes approval.
- **Independent second-person approval:** the approver must hold the privileged `exam.results.approve` and must not be the reviewer.
  This is enforced in code (`mayDecide`) and in the database (`CHECK approved_by <> reviewer_id`).
  - The approval is invalidated if any score changed after review (the hash is recomputed).
- **Publication:** scores become RELEASED **only** if a PUBLISHED review covers the attempt, and a database guard enforces that.
- **Candidate visibility:** own attempt only; 409 `result_not_published` before release.
- **Audit:** review, approval or rejection, and publication are audited with identifiers only.
- **R-1** (who reviews and who approves) is recorded as *Proposed*. It **needs your confirmation**.

## Open issues

| Issue | Severity | Status after Day 3 |
|---|---|---|
| KI-83 question images for candidates | Medium | **Fixed, CI-verified** (`ff4bfac`, run 43612273755). Images reach only the candidate's own open attempt, only for questions in that paper, only after a clean malware scan (unscanned development uploads only in development and test), never cached. Scheduling refuses a version with any undeliverable image |
| KI-84 browser E2E of the candidate exam | Medium | **Fixed, CI-verified** (run 43612273755) |
| KI-85 exam list not refreshed after check-in (found by Session 3) | Medium | **Fixed, CI-verified**: fixed in `735413a`; its QA test asserts the fix |
| KI-79 other devices signed out after a password reset | Medium | **Reopened**: failed once in CI run 43607780575; root cause not yet known |
| KI-87 (new) security headers duplicated behind both nginx proxies | Low | **Fixed, CI-verified**: the proxy configs send each header once, verified with the production nginx image. My first attempt (in the controller, `191c8b9`) was wrong and failed again in CI run [43610232127](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43610232127) |
| KI-88 (new) mobile students E2E: sign-in callback slower than 5 s in CI | Low (test) | Open; not caused by KI-87 (passes locally behind the production proxy config); cause unproven |
| KI-86 (new) enrolment history degrades to ~17 s without planner statistics | Medium | **Open**. Found while verifying the QA merge, from a virtual-thread dump and `EXPLAIN ANALYZE`. Remedy: `ANALYZE` in the restore and crash-recovery runbook; make the query independent of statistics |
| KI-75 question search at 60k/100k | Medium | **Open.** Session 2 reproduced it from `main`: p95 130 ms at 60k (target < 250), 122 ms at 100k (target < 300). This is **local, not CI**, and was run by the session that wrote the fix, so it is not independent in person |
| KI-80 feature-flag cache | **Medium** (was Low: it is a product defect) | **Root cause found** by Session 3: Spring Data Redis 4 writes the cache asynchronously, so a put can land after its eviction and a changed feature flag can keep its old value for up to 10 minutes. I reproduced it independently: 137 of 500 evicted entries were still readable. Fix: synchronous (immediate) writes, still transaction-aware; `FeatureCacheOrderingIT` now passes, and `FeatureFlagIT` 11/11. **CI-verified** |
| KI-81 E2E selections by position | Low | Test fix merged (Session 3: selections by this run's records); students and academics 12/12 locally; **CI-verified** |
| KI-82 scheduling reads other modules' tables | Low | Documented, deferred (D-11): no security, isolation or correctness impact found |
| KI-66 layered check-in limits (Session 3, `ws-c/m3c-delivery-hardening`) | Medium | Not yet reviewed or merged |

## Security test results

- **Delivery security regression suite** (Session 3, `DeliverySecurityRegressionIT`, 6 tests):
  - no answer information in any delivery response, audit record, event or log;
  - another candidate's attempt returns not found for read, save and submit;
  - no saves after submission or after the deadline;
  - schedules, enrolments, codes, attempts, answers and scores are isolated by tenant;
  - tokens from the wrong realm, and staff without the permission, are refused.
- **Image endpoint over HTTP** (Session 3, `AttemptImageSecurityIT`, 4 tests):
  - own open attempt: 200 with `no-store, private`, `nosniff`, inline;
  - another candidate's attempt, an unlinked, random or other organization's asset, and another organization's portal: one identical 404;
  - staff token or no token: 401;
  - after submission and after time-up: 404.
- **Browser edge cases** (Session 3, `attempt-edge.spec.ts`, 6 tests, 6/6 here):
  - four offline/online cycles;
  - two tabs on one attempt (the newest wins);
  - reload while a save is pending (the save is resent);
  - device clock +17 / −23 min (the countdown follows the server);
  - submit while a save is in flight (submit is blocked while unsaved; the late save gets 409 and is never acknowledged).
- **Concurrency** (`AttemptConcurrencyIT`, 3 tests): one attempt per candidate, one submission, and the highest answer sequence is kept.
- **KI-83 access:**
  - another candidate, an image of a question outside the paper, an unused upload, an unknown id, an image found infected later,
    and any image after submission all return 404, and refusals are audited;
  - outside development and test, unscanned images are never delivered;
  - E2E: no token → 401, `Cache-Control: no-store`, `nosniff`.
- **Result approval:** self-approval refused (E2E and IT); database guards against release without a published review.
- **Full backend suite:** 466/466 locally before pushing `191c8b9` (all merged QA tests and the KI-80 regression), and green in CI on every push today after `735413a`.

## Parallel sessions

| Session | Delivered | Merged | Notes |
|---|---|---|---|
| Session 2 (`ws-b/`) | Independent reproduction of the KI-75 benchmark (`e254c90`) | `bf7be55` | Docs only. Told to stand down: no further usage approved |
| Session 3 (`ws-c/`, QA) | Security regression suite, concurrency ITs, browser attempt and recovery specs, QA process script, KI-81 test fix; found KI-85; **root cause of KI-80** with a deterministic regression test; HTTP security tests for the KI-83 image endpoint; six browser edge cases | `6569c32`, `8364c27`, `a7af180` (the KI-80 production fix is mine) | Tests only, as D-7 requires. Reviewed and run by the primary session before merging |

## Remaining work (engineering days, one primary session)

| Item | Effort |
|---|---|
| Invigilator workflows (roster, live status, incident notes) | 1 |
| Review and merge KI-66 (Session 3's delivery hardening) | 0.5 |
| KI-86 query robustness and runbook | 0.5 |
| KI-79 (reopened) root cause | 0.5 |
| Web E2E for the staff results screen; R-1 adjustments after your decision | 0.5 |
| Load test at target concurrency on the delivery process; failure drills | 1.5 |
| Day 7 demo, documentation, final verification | 1 |
| **Total** | **≈ 5.5 days** |

## Day 7 feasibility

**At risk: about 5.5 days of work for the 4 remaining days (Day 4–7).** The candidate journey and the result workflow, the two
largest Day 3 items, are built and tested end to end.

If nothing else slips, the most likely to move beyond Day 7 is the **load test at target concurrency** (1.5 days), which needs a
production-like environment that this container cannot represent. Invigilator workflows remain planned for Day 4. Nothing is removed
from the contractual scope.

**Beyond Day 7 regardless** (production blockers, not engineering days in this container):
- ClamAV in the deployed environment;
- the DPDP/legal review;
- an external penetration test;
- data-key management;
- real-device Safari (KI-78) and KI-77.

These stay open until independently verified.

## Decisions needed

1. **R-1:** confirm that exam controllers and administrators review results and that only exam controllers (privileged) approve publication, with the reviewer never approving.
2. **KI-75:** accept a person-independent rerun by Session 3 (cost: about 30 minutes of usage), or keep KI-75 open until a later CI or performance environment exists.
