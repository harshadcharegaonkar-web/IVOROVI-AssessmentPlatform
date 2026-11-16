# 14-day demo programme: workstream charter (approved 2026-11-16, D-18)

Every session reads this file, `day-1-assessment.md` and the existing code it touches **before** writing code.

## Calendar

Day 1 = 2026-11-16 … Day 14 = 2026-11-29.

## Ownership

| Session | Workstream | Owns (modules, backend packages, routes) | Deliverables (accepted per `day-1-assessment.md` §7) |
|---|---|---|---|
| **A** (`session_019xkx8phRJetHCNvzFyn4sN`) | Technical lead; Examination | Architecture, shared API contracts, integration decisions, **final merge approval**. Packages `questions`, `assessments`, `scheduling`, `exams` (existing) | Mandatory D (descriptive type), F (admit card), G (manual evaluation, rubrics, double marking, moderation, re-evaluation, ranks, scorecard), H (assessment and result analytics, result notifications) |
| **2** (`session_019xkx8r4b4D93yeXL9o3MAC`) | Core platform, enterprise, coding | Existing `tenancy`, `identity`, `academics`, `students`, `features`, `platform` (shared: changes reviewed by A); new `platformadmin`, `coding`, plus the separate code-runner service | Mandatory A, B, C, I gaps; add-on I (tenant provisioning, branding, access administration, monitoring, backup and recovery, deployment configuration); add-on D (coding: runner per ADR-0004, editor, test cases, verdicts) |
| **3** (`session_019xkx8oYw3Yct98ZXZJrXg3`) | Integrations, demo data, QA, deployment | Existing `integrations`; new `sso`, `lti`; `scripts/demo/`, the demo environment, the SBOM | Add-on H (SSO brokering, LTI 1.3 with Moodle, webhook and API workflows); the interconnected demo dataset for all 18 modules; demo environment and reset; QA and security regression evidence; demo script |
| **4** (`session_019xkx8oyrjLRCCT4aocNykc`) | AI, proctoring, advanced assessment | New `ai`, `proctoring`; inside `questions`, `assessments` and the exam app **only** for new question types and sections, through contracts agreed with A | Add-ons A, B, C |
| **5** (`session_019xkx8oNt3TtuiwC4LhQAhN`) | Admission, analytics, placement | New `admissions`, `analytics`, `placement` | Add-ons E, F, G |

## Rules (no duplicates, no conflicts)

1. **Reuse, never duplicate.**
   - Admission entrance tests, placement aptitude and technical tests, and proctored and coding examinations all run on the **existing** examination engine: schedules, attempts, journal, scoring, results approval.
   - Uploads use the existing quarantine and ClamAV path.
   - Approvals use `ApprovalService`.
   - Notifications use the existing outbox and channels.
   - Audit uses `AuditLog`.
   - Encryption uses `FieldCipher`.
2. **Modules.**
   - New code goes in its own Spring Modulith module. `ModularityTest` must stay green: no use of another module's `internal` types.
   - Cross-module calls go through a public API agreed with A, in the owning module's top-level package.
3. **Shared modules.** A change to a module you do not own is a small, separate commit on your branch, flagged in your report. A reviews it first.
4. **Contracts first.** New REST endpoints are agreed in the OpenAPI document with A before the UI is built.
5. **Migrations.** Version `V<yyyymmdd><s><nnn>`: the date is the day you write it; `s` is the stream digit (A=0, 2=1, 3=2, 4=3, 5=4); `nnn` is a sequence. Example: Session 4 on Day 3 writes `V202611183010`. Every new table holding organization data has `tenant_id` and the RLS policy. Append-only and immutability guards follow the existing patterns.
6. **Branches.** `ds/<session>-<topic>` (for example `ds/s4-proctoring`). Push at least twice a day. **Never push to `main`.** A merges.
7. **Before you ask A to merge:**
   - the full backend suite is green on your branch, rebased on the current `main`;
   - frontend typecheck, lint and unit tests pass;
   - the browser E2E tests of your workflow pass;
   - your report lists the commits, test counts and anything unfinished.
8. **Security non-negotiables:**
   - tenant isolation (RLS), RBAC, audit without personal data or secrets;
   - answer-key protection (ADR-0009);
   - human approval for consequential decisions (AI output is a suggestion until a person accepts it; proctoring raises flags, people decide);
   - coding isolation (ADR-0004: never in the application server);
   - no secrets in the repository;
   - synthetic data only;
   - no new library or container without approval (approved list below).
9. **Honesty.**
   - No static screens, mocked services or stubs presented as workflows.
   - No retries or longer timeouts to make tests pass.
   - Report a blocked dependency or a capacity shortfall the same day; never reduce scope silently.
10. **Commits.** The environment's own identity, signed (D-13); never `-c user.name/user.email`. End messages with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and your own `Claude-Session:` link.

## Approved components (D-19; demo programme only, not production approval)

Each is recorded with its exact version and licence in `docs/demo-programme/sbom.md`.

| Component | Status | Conditions |
|---|---|---|
| CodeMirror 6 (MIT) | Approved | Browser code editor for coding assessments |
| OpenPDF (LGPL/MPL) | Conditionally approved | Verify the exact version, licence obligations and distribution implications **before** integration; record them in the SBOM |
| Prometheus + Grafana | Approved | Access restricted; no sensitive data exposed in metrics or dashboards |
| Moodle | Approved with conditions | A separate external service; a genuine LTI 1.3 integration, demonstrated |
| ClamAV | Approved with conditions | Scans admission documents before acceptance; failed, unavailable or infected scans are never treated as clean |

**Nothing else without approval.**

## External dependencies (status)

| Dependency | Status | Effect |
|---|---|---|
| AI provider and key | **Not available** (2026-11-16) | The AI add-on is built against a provider interface and tested with a recording test double **in tests only**. It cannot be accepted, or shown as operational, until a real provider key is configured |
| Code-execution host (gVisor or KVM) | DevOps to provide by Day 3 | Coding acceptance depends on it |
| Demo hosting environment (and backup) | Requested for Day 9 | Session 3 tracks it |

## Integration rhythm

- A merges reviewed branches at least twice a day (around 10:00 and 18:00 UTC) and runs the regression on the merged `main`.
- Each session sends A a short status message at each integration point:
  - done since the last one;
  - branch and commits;
  - tests;
  - blockers;
  - person-days remaining.
