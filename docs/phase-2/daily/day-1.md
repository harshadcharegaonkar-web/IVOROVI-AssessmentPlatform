# Seven-day execution — Day 1 report (2026-11-09)

Programme Day 1 of 7. Primary session (Session A). Integrated build on `main`: commits `077cfff`, `d1c7c4a`, plus the M1 UI commit of this report.

## Completed modules
- **KI-79 fixed** (CI evidence). The first fix (`3aafa97`) failed in CI; reproduced against real Keycloak 26.8. Three causes found: the
  `UPDATE_PASSWORD` event has no `sessionId` (the session id is `details.code_id`); Keycloak session start times have second resolution;
  and the E2E assertion itself was wrong (exam tokens are memory-only). Fixed and covered by tests built from the real event shape.
- **M1 assessment authoring — backend complete**: schema with database guards (immutability, publication policy, approved-only items,
  frozen pools, append-only transitions, immutable papers), RLS on all nine tables; permissions `assessment.read/author/author.internal/review/publish`
  (`assessment.publish` privileged, Q-2.2-1); governance per Q-2.2-2; pool rules per Q-2.2-5; retirement warnings per Q-2.2-3; secure,
  encrypted, reproducible paper seeds per Q-2.2-4; module API `ApprovedQuestions` (candidate projection only).
- **M1 UI (Day 2 work started early)**: assessment list and creation, draft editor (settings, approved-question picker, random rules with live
  pool size and exposure warning, totals), detail with workflow actions, validation, reviews, versions, history, review/publication queue,
  paper preview with reproduction and audited seed disclosure.

## Working end-to-end journeys
- **Journey 1 (M1)** — author (org admin) creates a semester examination from approved questions and a random rule → institution admin
  reviews → exam controller publishes (three different people) → paper previewed without answers → reproduced from its seed.
  Real Keycloak, API, database. `apps/web/e2e/assessments.spec.ts`: 3/3 passed locally, includes accessibility checks.
- Candidate password reset keeps the resetting device signed in and signs out other devices (KI-79): passed in CI without retry.

## Test results
| Suite | Result |
|---|---|
| Backend (local, full) | 431 tests, 0 failures (new: PaperGeneratorTest 9, AssessmentLifecycleIT 11, RecoverySessionSelectionTest +2) |
| CI run 42553836692 (`d1c7c4a`) | All four jobs green: frontend, backend, security scan, E2E (99 staff + 8 exam) |
| Exam E2E, KI-79 test (local) | 3/3 passed, `--retries=0` |
| Staff E2E (local, desktop, with M1 UI) | 64 passed, 1 failed: `students.spec.ts` (KI-81, test isolation on a reused local database; passes in CI) |
| Live smoke (local stack) | create / list / validate assessment with real Keycloak tokens |

The M1 UI commit has not yet been through CI at the time of writing; its run is linked in the conversation.

## Outstanding defects
- **KI-80** (new): `FeatureFlagIT` cache-invalidation test failed once in CI run 42472471985; root cause not established. Open.
- **KI-81** (new, low): index-based batch selection in `students.spec.ts`.
- Cosmetic: assessment item excerpts include option text (the excerpt is the question's full plain text). To be addressed with M1 UI polish.
- KI-75 (search performance) unchanged: assigned to Session B, not started (see dependencies).

## Security and production blockers
Unchanged and still open: ClamAV scanning, DPDP/legal review, external penetration test, data-key management, real-device Safari (KI-78),
KI-77, load test at target concurrency. No production readiness is claimed.

## Remaining work and effort
- Day 2: M1 UI polish (excerpt, Faculty journey, mobile), Journey 1 in CI, start M2 (scheduling, enrolment, bulk upload and invitation).
- Days 3–7 as planned (M3 delivery, M4 scoring and results, Day 7 hardening). Estimate for the full platform unchanged (≈70–75 engineering
  days, delivery-baseline §2).
- **Schedule risk:** the two parallel sessions produced no work on Day 1 (see below). If they do not deliver from Day 2, the Day 7 scope
  must be reduced or moved; I will say which on Day 3 at the latest.

## Dependencies and decisions needed
- **Parallel sessions (D-4):** both stopped about 13 minutes after starting, on the account's 5-hour usage limit, and pushed nothing.
  They have been told to resume and push their branches (`ws-b/m5-question-bank`, `ws-c/m3c-delivery-hardening`). Running three sessions in
  parallel needs a usage allowance that covers them; please confirm whether the plan can rely on that.
- No other decisions needed today.
