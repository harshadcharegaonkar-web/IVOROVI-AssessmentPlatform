# Seven-day execution — Day 4 report (2026-11-14)

Scope set by the Day 3 review: R-1 approved; KI-75 independent rerun approved (D-12); commit attribution investigated; Day 4
priorities 1–4. Synthetic data only, real services. **No production readiness is claimed.**

## Commits on `main` (normal pushes; no history rewritten)

| Commit | What |
|---|---|
| `08d1c3f` | Decision register: R-1 approved; D-12 (KI-75 rerun); D-13 (commit attribution) |
| `81f2ec0` | R-1: closed reviews audited; one test of the whole separation-of-duties model |
| `fea4261` | KI-79: a password reset always ends pre-reset sessions; failed sign-outs are retried |
| `1015817` | Merge of Session 3's KI-66 layered check-in limits (reviewed; migrations renumbered) |
| `037c2c4` | KI-86: enrolment history independent of planner statistics; restore runbook |
| `5f12a5b`, `9bb8a1e` | Session 3's independent KI-75 rerun (cherry-picked without its unapproved script); KI-75 resolved |
| `5aea60e` | Invigilation: live monitoring and permanent candidate incidents; KI-90; exam load harness |
| `02b7d65`, `7bb3d77` | Harness fixes and the 300-candidate evidence; register updates |
| `f591b04` | Staff E2E locator fix (a test defect I introduced with the monitor card) |
| `f3e32b2` | **KI-91** (found by the 1,000-candidate run): check-in idempotent for the candidate who used the code |
| (this commit) | This report and the 1,000-candidate evidence |

**Commit attribution (D-13).** I inspected the hook. It is `~/.claude/stop-hook-git-check.sh`, set by the cloud environment, not by this repository. It
flags unpushed commits whose committer is not the environment's signing identity (`noreply@anthropic.com`, SSH key in
`/root/.gitconfig`), because GitHub then shows them as *Unverified*. Since the first commit I had committed under your name and
e-mail. That was my own choice, not repository policy or your instruction, and it attributed my work to you. From Day 4 commits use the
environment's identity (*Claude*, signed; GitHub shows `08d1c3f` as **verified**). No existing commit was rewritten.

## CI evidence

| Run | Commit | Result |
|---|---|---|
| [43635759685](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43635759685) | `5aea60e` | Backend, frontend and security green; **E2E failed**: my new monitor card made a staff-test locator ambiguous (two "Arjun K" cells). A test defect, fixed in `f591b04` |
| [43637392954](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43637392954) | `f591b04` | **All jobs green, first attempt, no test retried, none flaky**: staff E2E 119 passed; exam E2E 22 passed, 1 skipped (opt-in crash test); the KI-79 password-reset test passed |

| [43640645145](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43640645145) | `3fc3d8c` | **All jobs green, first attempt, no test retried**: staff E2E 119 passed; exam E2E 22 passed, 1 skipped. Includes the KI-91 fix; the KI-79 test passed for the second consecutive run |

Full backend suite locally before each push: 476/476 (KI-66 merge), 477/477 (invigilation), 477/477 (KI-91).

## Completed functionality

**R-1 result governance (approved), implemented and tested.** `AttemptIT.resultGovernanceFollowsTheApprovedSeparationOfDuties` checks:
- only EXAM_CONTROLLER holds `exam.results.approve`, and it is privileged;
- exam controllers and both administrator roles may review;
- an institution administrator reviews, cannot approve their own review, an organization administrator cannot approve
  either, and an exam controller approves;
- a second controller can reject, and the reviewer can withdraw;
- the database refuses reviewer = approver even on a direct write;
- review, approval, rejection, withdrawal (new audit `exam.results.review_closed`) and publication are all audited, with
  identifiers only (no names, e-mails or scores).

**Invigilation (Priority 3).**
- **Who:** new permission `exam.invigilate` for the existing EXAM_PROCTOR role, exam controllers and administrators
  (administrators must hold it to assign the role).
- **Live monitor** per scheduled examination, refreshed every 10 s:
  - each enrolled candidate's state (not signed in / not checked in / checked in / writing / submitted / submitted at
    time-up), connection, answered of total, time left and incidents;
  - e-mails are masked;
  - never answers, keys or scores.
- **Candidate incidents:**
  - six categories;
  - permanent: a database guard plus no UPDATE/DELETE grant;
  - a correction is a new incident that refers to an earlier one of the same candidate;
  - audited with identifiers and category only, never the note.
- **Access:** invigilators can open schedules but not the enrolment list.
- **Existing authorized controls:**
  - extra time before the start needs `exam.schedule.manage`, a reason, and is audited (`exam.enrolment.extra_time`);
  - time added during an exam goes through four-eyes compensation (`exam.incident.decide`).
- **Tests:** `InvigilationIT`, run through the real delivery API; exam E2E monitor checks while the candidate writes and
  after submission; staff E2E monitor and incident dialog.

**Security and regression (Priority 1).**
- **KI-79:** two defects in the password-reset guard, each able to leave another device signed in:
  - an unrelated logout between the reset request and the new password made the guard skip the sign-out;
  - one failed call to Keycloak was never retried.
  Both are fixed and tested. Locally the browser test passed 5/5 against real Keycloak, and it passed in CI on the first
  attempt. **Which defect caused the earlier CI failure is not proven**: the local Keycloak event sequences contained no such
  logout. It stays open until it has passed on repeated CI runs.
- **KI-66 (Session 3), reviewed and merged.** Layered check-in limits. Shared-address evidence:
  - 1,000 candidates from one address all checked in with no 429;
  - 20 attackers on the same address made 0 successful guesses.
  Review finding recorded as **KI-89**: the limiter fails open when Valkey is down, so the per-code lock does not fire
  during a Valkey outage.
- **No regression in tenant isolation, candidate authentication or answer-key security.** The suites below pass on every
  push today:
  - delivery security regression;
  - image access;
  - result workflow;
  - concurrency;
  - question answer-key leak tests.

**Open defects (Priority 2).**
- **KI-86 fixed:** reproduced in the incident state; former query 216,839 ms, new query 1.3 ms. Restore runbook added.
- **KI-75 resolved** on Session 3's independent evidence: p95 ≤ 74 ms sequential and ≤ 168 ms with 8 clients at 100,000
  questions.
- **KI-88 investigated, not resolved:** the KI-86 hypothesis was tested and rejected. No retries were added and no timeouts
  raised.

## Load and resilience evidence (Priority 4)

`backend/devtools/harness/ExamHarness.java` takes **real examinations** through the real APIs:
- **Setup:** bank, approved questions, published assessment, portal, schedule, CSV enrolment and session codes through the
  staff API, respecting four-eyes.
- **Candidates:**
  - every virtual candidate signs in, checks in with its own code, starts and answers;
  - every save is retried until acknowledged, as the exam app does;
  - every acknowledgement is recorded.
- **Behaviours:**
  - all candidates use **one network address** (shared campus NAT);
  - every 10th candidate goes offline for 20 s;
  - every 7th sends two concurrent submissions and a replay;
  - every 5th never submits and is left to automatic submission.
- **Failure:** the delivery process is **SIGKILLed** mid-run and restarted.
- **Checks after the exam:**
  - every candidate's last acknowledged answer for each question is stored with at least that sequence;
  - exactly one submission per attempt;
  - two concurrent result reviews and two concurrent approvals, of which exactly one each must succeed.

| | Run A | Run B (before the KI-91 fix) | Run C (with the fix) |
|---|---|---|---|
| Candidates / questions / duration | 300 / 10 / 4 min | 1,000 / 10 / 4 min | 1,000 / 10 / 4 min |
| Delivery process SIGKILL | at +100 s (mid-exam), back after 13 s | at +15 s (during check-in and start), back after 14 s | same as B |
| Checked in / started | 300 / 300 | **998 / 998** (2 refused: KI-91) | **1,000 / 1,000** |
| Submitted by candidate / automatically at time-up | 240 / 60 | 799 / 199 | 800 / 200 |
| Scored (provisional) / still in progress | 300 / 0 | 998 / 0 | 1,000 / 0 |
| Acknowledged final answers checked / **lost** | 3,000 / **0** | 9,980 / **0** | 10,000 / **0** |
| Saves acknowledged / retried during the outage | 18,213 / 1,973 | 54,702 / 3,313 | 53,562 / 1,877 |
| Duplicate submitters (inconsistent results) | 35 (0) | 115 (0) | 115 (0) |
| Concurrent reviews / approvals succeeded | 1 of 2 / 1 of 2; published once (300) | 1 / 1; published 998 | 1 / 1; published 1,000 |
| Save latency p50 / p95 / p99 / max | 8.9 / 24.3 / 443 / 2,751 ms (max includes the outage) | 8.5 / 1,623 / 4,183 / 11,245 ms | 8.6 / 1,980 / 3,637 / 11,958 ms |
| Check-in p95 / start p95 | 81 / 104 ms | 8,118 / 4,149 ms | 9,060 / 4,521 ms |

Raw results: `docs/performance/exam-harness-day4-300.json`, `exam-harness-day4-1000.json` (run B), `exam-harness-day4-1000-fixed.json` (run C).

**Defect found by run B (KI-91, fixed and re-run as C).** Two candidates were refused at check-in with "code not valid".
- Cause: their first check-in had committed, but the process was killed before the response arrived; the retry then found
  the code already consumed by that same candidate. The database held 1,000 check-ins for 998 attempts.
- In a hall, those candidates would have been told their code was invalid.
- Fix: a retry by the candidate who used the code returns their existing check-in. Run C: 1,000 of 1,000 started, 0 problems.

**Latency under these runs, stated plainly.** In runs B and C, check-in and start p95 are 4–9 s. Three causes overlap:
- the 14-second outage;
- the retry surge after the restart;
- the deliberate per-address delay of the KI-66 design, because all 1,000 candidates come from **one** address.
A registered venue network switches that delay off. Saves outside the outage window stay at a p50 of about 9 ms.

**What these runs do and do not prove.**
- **"0 lost" applies only to these runs:** for every candidate and question, the last answer the server acknowledged is
  stored. Earlier saves of the same question are superseded by design.
- **One machine:** everything shares one 4-vCPU container: the load generator, the API, the delivery process and the
  infrastructure. These are not production-scale numbers.
- **Not at target concurrency:** the target-concurrency load test on production-like infrastructure is still open.
- **One shared network address:** that was the intent here (shared NAT).
- **Not covered:** browser-level network loss at scale, and a PostgreSQL or Valkey failover. Single-candidate browser
  offline and crash recovery are covered by the E2E suites.

## Session contributions

| Session | Day 4 | On `main` |
|---|---|---|
| Session 2 (`ws-b/`) | None today (stood down after its Day 3 reproduction) | — |
| Session 3 (`ws-c/`, QA) | KI-75 independent rerun (about 11 minutes, D-12); KI-66 layered limits (earlier branch, reviewed and merged); result-workflow and image-endpoint security tests; browser edge cases; KI-80 root cause | `5f12a5b`, `1015817`; earlier merges `6569c32`, `8364c27`, `a7af180`, `8df2a03` |

Session 3 started a paper-generation benchmark before my stop message arrived. It stopped, kept no results, and I did not
merge that script.

## Open defect register (Day 4 changes)

| Issue | Severity | Status |
|---|---|---|
| KI-79 password reset signs out other devices | Medium | Fix passed in 2 consecutive CI runs (first attempt each); kept open for more evidence |
| KI-88 mobile students E2E sign-in callback in CI | Low | Open; hypothesis rejected; cause unknown |
| KI-91 check-in retry after a lost response refused | Medium | **New, fixed, CI-verified** (run 43640645145); the identical harness scenario gives 1,000/1,000 |
| KI-89 code guessing bounded only by the code space during a Valkey outage | Low | Open (new): durable fallback proposed |
| KI-82 scheduling reads other modules' tables | Low | Deferred (documented) |
| KI-66, KI-86, KI-90 | Medium | **Fixed, CI-verified** (run 43637392954) |
| KI-75 | Medium | **Resolved**, independently verified |
| KI-80, KI-81, KI-83, KI-84, KI-85, KI-87 | — | Fixed, CI-verified (Day 3) |

## Production-readiness gates (unchanged, all open)

- Real malware scanner (ClamAV) deployed and validated.
- Encryption-key custody, backup and recovery.
- External penetration test and remediation.
- DPDP legal review.
- Real-device Safari testing (KI-78).
- Screen-reader validation: automated axe checks pass in the E2E suites; a manual screen-reader pass is still open.

The platform is **not** production-ready until these gates have passed.

## Remaining engineering effort

| Item | Days |
|---|---|
| Load test at target concurrency on production-like infrastructure (multiple delivery instances, database failover) | 1.5 |
| KI-89 durable fallback; KI-88 root cause; KI-79 confirmation | 0.75 |
| Invigilation follow-ups: proctor self-service (the monitor in the exam controller's dashboard list), live alerts on disconnects | 0.5 |
| Day 7 demonstration, documentation, final verification | 1 |
| **Total** | **≈ 3.75** |

## Day 7 feasibility

**Achievable for the engineering scope, with one item at risk.** About 3.75 days remain for Days 5–7.

The item at risk is the **load test at target concurrency on production-like infrastructure**. This container is one
shared 4-vCPU machine: it cannot host multiple delivery instances under real load, a database failover, or a separate load
generator.

- **Proposal:** Day 5–6 runs on this environment at the highest concurrency it sustains, with the same harness. The
  target-concurrency run on production-like infrastructure moves beyond Day 7.
- **Revised target:** within 2 working days of that environment being available.
- **Reason:** that infrastructure is not available here.

Nothing is removed from the contractual scope.
