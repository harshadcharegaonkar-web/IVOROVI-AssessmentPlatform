# Demo programme: Day 1 report (2026-11-16, issued late on Day 2 at 05:00 UTC)

This report was due at the 18:00 integration point. It is late because every session, this one included, stopped on
the account's shared 5-hour usage limit (see **Capacity**).

## 1. Capacity incident (critical, reported now)

**What happened.** At 15:03–15:10 UTC all four parallel sessions stopped with `usage_limit_reached` ("You've hit your
5-hour limit · resets 7:30pm UTC"). The limit is shared across the account, so five sessions working in parallel exhaust
it in about 10 hours of combined work.

**Who lost time:**
- **Sessions 2 to 5** were idle from about 15:05 UTC to 04:50 UTC on Day 2 (13.7 hours). Of those, 9.3 hours came after
  the limit reset, because nothing resumed them. **I resumed all four at 04:50 UTC.**
- **Session 5** had also been paused from 05:35 to 14:40 UTC on Day 1.
- **This session** had several machine restarts, each recovered within minutes, plus the same stop.

**Effect.** Day 1 delivered roughly 45% of one planned day across the five sessions (about 2.2 of 5 agent-days).

**Risk.** If the limit stays as it is, this repeats every day. Effective capacity would then be about 50–60% of plan,
roughly 35–45 agent-days over the remaining 13 days against about 80 needed. **That is not enough for all 18 modules.**

**Decision needed from you (see §9):**
- (a) raise the account's usage limit for the programme; or
- (b) stagger the sessions so at most two or three run at once, with the scope consequences that follow; or
- (c) reduce the number of parallel agents and accept a smaller scope.

I do not reduce scope myself.

## 2. Work completed by workstream

| Stream | Completed on Day 1 | Branch and commits |
|---|---|---|
| **A** (lead, Examination) | Contracts C-1 (question types, marking modes, scoring hook) and C-2 (manual evaluation). **M-D descriptive question type merged**: authoring with a word limit, encrypted model answer and rubric; exam-app written answer with a live word count; deferred scoring until evaluators mark; `ItemResults` hook; awaiting-evaluation results. **M-G part 1+2 built** (not yet merged): anonymous scripts, setup, balanced allocation, rubric marking, single and double marking, moderation, the AI-assistance API, the staff UI and the evaluator UI. Decisions on the plans of Sessions 2–5. Review and approval of Session 4's sections branch | main: `48836d7`, `7409deb`, `0551f28`, merge `7ddad78`, `b297657`. ds/sA-evaluation: `5ec44a9`, `1939502` |
| **2** (platform, enterprise, coding) | Mandatory I: security policy (MFA required, idle and absolute session limits, a read-only password-policy statement, four-eyes approval for weakening a policy) | ds/s2-security-policy: `bd1d434`, `a5bea22`, `fbf5f92`, `39b929c` (full suite and E2E pending) |
| **3** (integrations, data, QA) | ClamAV in the stack (opt-in overlay, pinned by digest) with real-scanner tests; SBOM (198 + 99 packages, 0 unknown licences); demo-data runner per the approved contract, with step 10 | ds/s3-clamav-sbom `646a872`, `b40f69a`; ds/s3-demo-data `5796444` |
| **4** (AI, proctoring, advanced assessment) | Add-on C part 1: sections and blueprint constraints, with generator v2 and a v1 golden test; staff editor, preview and exam-app sections; E2E. Proctoring facades started | ds/s4-sections `fa73a46` (approved; waiting on its rebase); ds/s4-proctoring `e93f6f1`, `449f261` |
| **5** (admission, analytics, placement) | S-1: shared quarantine pipeline `platform.documents.QuarantinedDocuments`, extracted from students with behaviour unchanged | ds/s5-shared-apis `b5fc5d9`, `fcbaaa0` |

## 3. CI runs

| Run | Commit | Result |
|---|---|---|
| 43889619541 | `7ddad78` (merge) | Cancelled. Replaced by the next push |
| **43890000557** | `b297657` (includes the merge) | **Success on all four jobs**: backend, frontend, end-to-end (real Keycloak), security scan |

## 4. Test evidence

- **Backend full suite on ds/sA-descriptive:** 538/538, run twice (before and after the migration renumber).
- **New backend tests:**
  - DescriptiveTypeTest 5;
  - DescriptiveScoringIT 3: deferral, validation, isolation, immutability, no key in delivery;
  - EvaluationWorkflowIT 2: setup validation, balance, anonymity, isolation, bounds, permanence, agreement, moderation.
- **Browser E2E (Chromium, local stack):**
  - questions.spec 9/9, four consecutive runs;
  - exam descriptive.spec and attempt.spec 6/6;
  - evaluation.spec 1/1: the controller sets up and allocates on the schedule page, and the evaluator marks on their
    own screen without seeing who wrote the answer.
- **Other streams (their own reports, not yet re-run by me):**
  - Session 4: 556/556 backend, web E2E 69/69, exam E2E 25 + 1 opt-in skip;
  - Session 3: 537 backend + 1 skipped (QA-DP-1), real-scanner tests 6 + 1 disabled;
  - Session 2: SecurityPolicyIT 3/3;
  - Session 5: students suite 39/39.
- **One existing staff test failed intermittently** ("a new version never changes the approved one"). It checked the
  page while it was still reloading. It now checks the final state; there are no retries.

## 5. Modules accepted

**None yet.** Acceptance needs the workflow on the demo environment with demo data (assessment §7), which does not exist
until Day 9.

M-D's workflow is complete on main and is the first candidate.

## 6. Modules still incomplete

All 18. The table shows Day 1 progress against the matrix.

| Module | Status |
|---|---|
| M-D | Built and merged; awaits the demo environment |
| M-G | Evaluation built (part 1+2, on the branch). Still to do: re-evaluation with score revisions, ranks, scorecard |
| M-I | Security policy built on Session 2's branch |
| AO-C | Sections and blueprint built and approved (merge pending the rebase); the three new question types are not started |
| AO-E | Shared quarantine extracted |
| Everything else | Not started, or contracts only |

## 7. Critical defects

| ID | Severity | Status |
|---|---|---|
| **QA-DP-1** | Medium | ClamAV reports CLEAN for an archive entry it scanned only partly. This breaks your rule that a failed scan must never count as clean. Confirmed; Session 3 is fixing it fail-closed in `platform.scanning` |
| KI-79, KI-88, KI-49 | — | Unchanged and open (from the 7-day programme) |

## 8. Infrastructure dependencies

| Dependency | Status |
|---|---|
| AI provider key | **Not available.** AO-A cannot be accepted |
| Code-execution host | Expected from DevOps by **Day 3 (tomorrow)**. Not yet confirmed |
| Demo hosting | Day 9 |
| Decisions needed by Day 3 | Hidden coding test cases sent to the runner (an exception to answer-key protection); the Moodle image route |
| New containers awaiting approval | Coding language images and the runner image; the demo webhook receiver |

## 9. Decisions needed from you

1. **Usage limit or parallelism (§1). Most urgent.**
2. Hidden coding test cases sent to the runner (needed by Day 3).
3. Whether a model answer may ever go to the external AI provider (the default is no).
4. The container images: coding images, the Moodle route and the webhook receiver.
5. Automated face detection in proctoring (not built unless you decide otherwise).
6. Result scores in webhooks (P2-12).
7. The capacity overruns (Sessions 2, 3 and 4 are each about 2–3 days over) and the sixth coding agent.

## 10. Remaining person-days and forecast

**Remaining work by session:**

| Session | Remaining (one-agent days) |
|---|---|
| A | ≈ 12: M-G ≈ 4.5, M-F 1, M-H 2, lead ≈ 4.5 |
| 2 | ≈ 17 |
| 3 | ≈ 18 |
| 4 | ≈ 17 |
| 5 | ≈ 18 |
| **Total** | **≈ 82** |

**Capacity:**
- Nominal: 5 agents × 13 days = 65.
- Effective at Day 1's rate: about 35–45.

**Delivery confidence (all 18 modules by Day 14): LOW.**
- Mandatory nine: medium-high.
- Add-ons: low, until the usage limit is resolved, the AI key arrives and the execution host arrives.

The 14-day target is not guaranteed. It needs the §1 decision to be achievable.
