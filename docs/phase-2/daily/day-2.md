# Seven-day execution — Day 2 report (2026-11-09)

Programme Day 2 of 7. Primary session (Session A). Report written at about 19:30 UTC, after the 16:00 checkpoint: this session
was paused for about 14 hours (05:10–18:58 UTC) and the parallel sessions produced no scheduling work (see below).

## Commits on `main` today (all normal pushes)

| Commit | Content | CI |
|---|---|---|
| `56df5c1` | Answer-protection checks (API, browser, audit, outbox, logs); statement-only excerpts | covered by later runs |
| `96d50c3`, `36212c2` | Interfaces: `PublishedAssessments`, `ExamSessions`, `ExamEligibility`, candidate email index | covered by later runs |
| `324bf27`, `db88399`, `d1b4fa2` | M3 delivery engine and exam-app attempt screen | covered by later runs |
| `6100884`, `5019a3a` | Merge of Session 2's KI-75 search (reviewed), migration renumbered | run 42560361816 — **all four jobs green** |
| `b290db8`, `b613cad` | M4 automatic scoring; baseline §6 ownership record | run 42565778068 — **all four jobs green** |
| `af9bd00` | M2 scheduling and enrolment (taken over from Session 2) | run 42682363623 — **all four jobs green**; checkpoint steps 1–4 passed in CI |

Day 1 closure (`5c3a211`): run 42556915621, all four jobs green, Journey 1 (author → review → publish) passed in CI.

## Modules completed and demonstrated

- **Answer protection (priority 1).** Automated, not manual: `AssessmentLifecycleIT.answersNeverLeaveTheQuestionModule` checks every
  assessment, picker, paper and history response, all audit details, outbox payloads and the captured application output; the
  E2E journey records every API response the browser receives. Excerpts now show the question statement only.
- **M2 scheduling and enrolment (priorities 3–6).** Schedules over the currently published version only, within its institution;
  bulk CSV enrolment with preview, per-line errors and idempotent re-upload; email invitations with delivery status; extra time with a
  reason; single-use session codes; cancellation; database guards and RLS; candidate linking by keyed email index.
- **M3 delivery engine (backend complete; browser E2E pending).** Attempts bound to the published version and a generated paper;
  deadline = duration + extra time, capped by the window; durable, ordered answer saves with an append-only journal; idempotent
  submission; automatic submission at time-up; exam-app screens for starting, answering (autosave, offline retry) and submitting.
- **M4 automatic scoring (provisional).** Marking inside the question module (`QuestionMarking`, keys never leave it); immutable
  per-answer and total scores with an evidence hash. Releasing results with four-eyes approval is not built yet.
- **Not done today:** invigilator workflows (priority 7).

## End-to-end checkpoint (real services, synthetic data)

Author → Reviewer → Publisher → Published Assessment → Candidate Enrolment → Scheduled Examination:
`apps/web/e2e/assessments.spec.ts` steps 1–4, real Keycloak, API, database and mail server. **4/4 passed locally (no retries) and 4/4 in CI run 42682363623.** Step 4 also confirms the invitation email arrives with the portal link.

## Automated tests

| Suite | Result |
|---|---|
| Backend, full (local, `af9bd00`) | **445 tests, 0 failures** (new today: AttemptIT 4, ScheduleIT 3, excerpt test 2, interface and leak tests) |
| Frontend unit, typecheck, lint | 39 web tests pass; exam app 8; lint and typecheck clean |
| Journey 1 E2E (local) | 4/4 |
| Exam bundle | initial 94 kB gzip ≤ 120 kB; KaTeX lazy chunk within the approved 80 kB (L-2.1-2) |

## Outstanding defects and gaps

- **KI-80** (feature-flag cache test) — open, no root cause yet. **KI-81** (E2E batch selection) — open.
- **KI-75** — fix merged; benchmark by Session 2 meets targets (p95 64 ms at 60k, 123 ms at 100k); independent re-run pending.
- **KI-82** (new) — scheduling views read other modules' tables in SQL; to be replaced.
- **KI-83** (new) — question images are not served to candidates yet; image questions must not be used in live exams.
- **KI-84** (new) — candidate exam-taking has no browser E2E yet (backend IT only).
- Results release with independent approval, candidate result view, invigilator workflows: not built.

## Security and production blockers

Unchanged and open: ClamAV, DPDP/legal review, external penetration test, data-key management, real-device Safari (KI-78), KI-77,
load test at target concurrency. Session 3's branch with layered check-in rate limits (KI-66) is pushed but **not yet reviewed or
merged**. No production readiness is claimed.

## Parallel sessions

| Session | Pushed | Merged | Status |
|---|---|---|---|
| Session 2 (`ws-b/`) | KI-75 search (`cb5817b`) and benchmark docs (`23f6130`, `2c4035e`) | `cb5817b` merged (6100884) | Hit the 5-hour usage limit at 05:11 UTC right after confirming the scheduling assignment; produced no scheduling code. **Session A took scheduling over** and told Session 2 not to start it |
| Session 3 (`ws-c/`) | `ws-c/m3c-delivery-hardening` (`ad08b98`: KI-66 layered limits complete with tests; KI-71–73 partial) | not yet | Waiting for **your** confirmation of the QA role before starting it: it will not accept the change from another session |

The account's usage limit is shared by all sessions, so three sessions do not add capacity when the limit is reached. Planning
assumption from Day 3: **one primary session carries the critical path**; parallel sessions take only independent, non-blocking work.

## Remaining work (re-estimated)

| Item | Effort | Planned |
|---|---|---|
| Candidate exam E2E (invitation → sign-in → check-in → answers → submit → score), question images for candidates | 1 day | Day 3 |
| Results: release with independent approval, candidate result view, staff results screen | 1 day | Day 3–4 |
| Invigilator workflows (roster, live status, incident notes) | 1 day | Day 4 |
| Review and merge Session 3's KI-66 branch; KI-80, KI-81 | 0.5–1 day | Day 4 |
| Hardening: load test at target concurrency, failure drills, security regression, Day 7 demo | 2 days | Day 5–7 |

## Is the Day 7 scope still achievable?

**At risk, not yet lost.** Authoring, scheduling, enrolment, delivery and provisional scoring exist and are tested; the remaining
Day 7 items are the candidate journey E2E, results release, invigilation and hardening (about 5–6 days of work for 5 remaining days)
with one effective session. If Day 3 does not close the candidate journey E2E and results release, I will propose on Day 3 which
Day 7 items move, with the reason; nothing will be dropped silently and nothing leaves the overall contractual scope.

## Decisions needed

1. Confirm in Session 3's own conversation (or tell me to stop it) whether it should switch to the QA role.
2. Whether to plan the remaining days on one primary session (my recommendation) or to provide additional usage capacity for parallel sessions.
