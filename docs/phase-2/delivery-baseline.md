# Full-platform delivery baseline (2026-11-08)

Status: **baseline for approval; single source of truth for scope and dates** (with `../tracker.md` for daily state
and `../known-issues.md` for defects).

Decisions in force:
- **D-2:** the Day-7 objective-examination milestone is approved. It is a milestone, not acceptance of the full scope.
- **D-3:** daily reviews.
- **D-4:** two additional Claude sessions.
- **D-6:** bulk upload and invitation of candidates for Day 7.
- The full agreed platform remains the delivery objective. **Nothing contractually committed is removed.**

Calendar:
- Day 1 = Monday 2026-11-09.
- Day 7 = Sunday 2026-11-15.
- Week 3–4 = 2026-11-23 to 2026-12-06.
- Week 6 ends 2026-12-20.
- Weeks 8–10 = 2026-12-28 to 2027-01-17.

Dates assume same-day decisions and the external owners in §5 starting this week.

## 1. Contractual scope matrix (D-1)

**Source of "agreed scope".** The signed contract is not in the repository. This matrix uses the scope recorded and
approved here:
- the 21 modules of the original requirements (`../tracker.md`, Phase 0);
- the Phase 2 blueprint approved 2026-11-04 (`README.md`, documents 01–18);
- the functional breakdown `02-functional-modules.md`.

**Please confirm or correct it against the contract.** Anything you mark "not contractual" is moved to "optional",
never silently dropped.

Columns:
- **C** = contractually committed (per the sources above; ✓ unless noted);
- **Status** = Done (implemented and verified in CI with persistence, authorization and tests), Partial, or Missing;
- **Ext** = depends on external infrastructure, vendors or approvals.

| # | Module / capability | C | Status | Ext | Notes |
|---|---|---|---|---|---|
| 1 | Institution & tenant management: hierarchy, settings, terminology | ✓ | Done | — | 1.1 |
| 1a | Tenant provisioning by platform operator, logo/branding | ✓ | Missing | — | Operator console; logo needs scanned upload (RB-1) |
| 1b | Controlled restructuring | ✓ | Missing | — | Designed (`architecture/06`) |
| 2 | Users, staff, invitations, roles, permissions, privileged delegation | ✓ | Done | — | 1.2, 1.3, P2-08 |
| 2a | Bulk user import; branded email templates | ✓ | Missing | — | Reuses the 1.6 import engine |
| 3 | Students: lifecycle, documents, accounts, bulk import | ✓ | Done | RB-1 | Student account suspension on exit (RB-4) missing |
| 3a | Candidates: applicant records, dedup, conversion to student | ✓ | Missing | — | 2.3, 2.7 |
| 4 | Admissions & eligibility: cycles, forms, eligibility, screening, merit lists, offers | ✓ | Missing | DPDP | 2.3, 2.7 |
| 5 | Question bank: objective types, governance, math, images, import | ✓ | Done | RB-1, KI-77/78 | 2.1 |
| 5a | Fill-in-the-blank, matching, ordering; secure banks; QTI import/export | ✓ | Missing | — | Blueprint 2.1 list |
| 6 | Assessment authoring: purposes, configuration, selection, randomization, blueprint, review, publication, versions | ✓ | Missing | — | Day 1–2 core; sections, templates and blueprint constraints in week 2 |
| 7 | Scheduling: events, sessions, venues, allocation, accommodations, codes, admit cards | ✓ | Missing | — | Day 3 core; venues and seats, admit-card PDFs in week 2 |
| 8 | Online examination engine: papers, attempts, timing, autosave, resume, idempotent submit, auto-submit | ✓ | Partial (portal, auth, check-in, durable save path) | — | Day 4–5 |
| 8a | Invigilator console: admit, monitor, extend, incidents | ✓ | Partial (incidents, compensation) | — | Day 5 |
| 8b | Delivery hardening: layered rate limits, partial-outage detection | ✓ | Partial | — | KI-66, KI-71–73; required before real delivery |
| 9 | Exam security & proctoring L1 (browser signals, device binding) | ✓ | Partial | — | Week 2 |
| 9a | Proctoring L2/L3 (snapshots, vision, review queue, appeals) | ✓ | Missing | DPDP, model hosting | 2.10 |
| 10 | Automated evaluation (objective scoring) | ✓ | Partial (per-type marking) | — | Day 6 |
| 10a | Manual evaluation, rubrics, double marking, moderation, re-evaluation | ✓ | Missing | — | 2.8 |
| 11 | Academic assessment management: internal and semester components, weightage, grades | ✓ | Missing (structure Done) | — | 2.9 |
| 12 | Coding & technical assessments (secure execution) | ✓ (ADR-0004 conditionally approved) | Missing | **KVM nodes** | 2.11 |
| 13 | Placement & employability: drives, recruiter portal, consent | ✓ | Missing | DPDP | 2.13 |
| 14 | Results & merit lists: computation, ranks, approval, publication, scorecards, queries | ✓ | Missing | — | Day 6 core; ranks, normalization, scorecards and queries in week 2; merit lists in 2.7 |
| 15 | AI evaluation & recommendations (off by default) | ✓ | Missing | **AI provider, keys, DPDP** | 2.12 |
| 16 | Analytics & reporting | ✓ | Partial (institutional dashboards and reports Done; assessment and result analytics Missing) | — | |
| 17 | Notifications & workflow: in-app, email, preferences | ✓ | Done | SMTP provider | Templates for admit cards and results come with those modules |
| 18 | Integrations & APIs: API keys, webhooks, REST adapter | ✓ | Done | — | 1.7 |
| 18a | SSO, LTI 1.3, OneRoster, exam/result webhook events | ✓ | Missing | **Reference LMS and IdP** | 2.14 |
| 19 | Audit & compliance: audit trail | ✓ | Done | — | |
| 19a | DPDP: consent, data-principal requests, retention | ✓ | Missing | **Legal counsel** | RB-5 |
| 20 | Subscription & licensing: plans, entitlements, metering, on-premises licences | ✓ | Missing | — | 2.15 |
| 21 | System administration: flags, settings | ✓ | Done | — | Maintenance windows missing |
| 22 | Deployment & operations: Helm, production Keycloak, DR, on-premises bundle, monitoring | ✓ (blueprint topologies) | Partial (Compose and CI only) | **DevOps, infrastructure** | 2.15, KI-14 |
| 23 | Release blockers RB-1 to RB-6 (scanner, key custody, rotation, account lifecycle, DPDP, pen test) | ✓ (production) | Open | **DevOps, security tester, counsel** | §5 |

## 2. Re-validation of the 30–33 engineering-day estimate

**It was incomplete.** It covered the module build (with tests at the usual bar), 3 days of cross-module integration
and regression, and 2–3 days of deployment work. It **did not** include the following items, all contractual or
production-relevant:

| Missing from the earlier estimate | Days |
|---|---|
| Missing question types (blank, matching, ordering), secure banks, QTI import/export | 4 |
| Assessment sections, templates and blueprint constraints (deferred from the Day 1–2 core) | 2 |
| Venues and seats, admit-card PDFs, accommodations detail | 2 |
| Delivery hardening KI-66, KI-71–73 beyond the Day-6 minimum | 2 |
| KI-75 search optimization (60k/100k targets) | 1.5 |
| Results ranks, normalization, scorecards with verification, queries and grievances | 2 |
| Assessment and result analytics | 2 |
| Tenant provisioning console, branding, bulk user import, email templates, maintenance windows | 3 |
| RB-3 key rotation tooling; RB-4 student account lifecycle | 3 |
| DPDP technical workflow (consent records, data-principal requests, retention jobs), after counsel's input | 4 |
| Security regression suite per module plus fixes from the penetration test (reserve) | 4 |
| Accessibility and real-device findings (reserve, after KI-77/78) | 2 |
| Integration overhead of parallel sessions (contracts, merges, conflicts), about 10% | 4 |
| **Additional** | **≈ 39.5** |

**Revised total: ≈ 70–75 engineering days (one-agent equivalent).** That is about 33 plus the 39.5 above, plus the
remaining module work spelled out per module in §3.

With three sessions in parallel, this is about **25–28 working days of elapsed time**. Week 6 (2026-12-20) is
achievable for the functional scope **only with all three sessions productive from Day 1 and no external blocker on
the critical path**. Coding (KVM) and AI (provider) are such blockers.

External work (penetration test, legal review, infrastructure) is **not** counted as done when code is written: §4
column 8 and §5 track it separately.

## 3. Module baseline (remaining work)

Columns:
- **Eff.** = engineering days, one-agent equivalent, including unit, integration, E2E and security tests at the 2.1 bar;
- **Test/int.** = extra days of cross-module integration and regression;
- **Par.** = can run in parallel (stream A, B or C);
- **Done by** = planned completion;
- **Prod.** = production-readiness status after completion.

| Module | Functional scope (acceptance criteria) | Eff. | Test/int. | Depends on | Par. | External | Done by | Prod. |
|---|---|---|---|---|---|---|---|---|
| **M1 Assessment authoring (core)** | Purposes; configuration; approved-version selection with subject/topic/difficulty filters; manual and random selection; difficulty distribution; marks and negative marks; validation; preview; review and three-person publication; immutable versions; references; seeds and papers stored (Q-2.2-1 to 6) | 3 | 0.5 | 2.1 | A | — | Day 2 (11-10) | Not ready: RB-6 |
| M1b Authoring completion | Sections, templates, blueprint constraints, withdraw, policy relaxation through four-eyes | 2 | 0.5 | M1 | A | — | Week 2 (11-20) | — |
| **M2 Scheduling (core)** | Examination events and sessions (fixed and window), candidate enrolment by bulk upload and invitation (D-6), allocation, session codes, extra time; sessions bind an assessment version permanently | 2 | 0.5 | M1 | A | — | Day 3 (11-11) | — |
| M2b Scheduling completion | Venues, rooms, seats, admit-card PDFs, accommodation types | 2 | 0.5 | M2 | C | — | Week 2 (11-21) | — |
| **M3 Delivery engine** | Sealed paper per attempt; server deadline; append-only response journal acknowledged only when durable; resume; idempotent submit; auto-submit; no keys in any delivery payload; chaos gate (zero lost acknowledged answers, 2,000 candidates, p95 save < 300 ms) | 4 | 1 | M2 | A (API) + B (UI) | — | Day 5 (11-13) | Pilot: needs RB-1/2/6, KI-66/71–73 |
| **M3b Invigilator console** | Admit, monitor progress, extend time, incidents (reuses 2.0) | 1.5 | 0.5 | M3 | B | — | Day 5 | — |
| **M3c Delivery hardening (R)** | Layered rate limits (candidate, code, device, address); per-instance and partial outage detection; impact and correlation; operator dashboard | 3 | 1 | M3 | C | — | Day 6 minimum; complete Week 2 (11-18) | Required for pilot |
| **M4 Scoring and results (core)** | Deterministic scoring from sealed paper and journal; result sets; four-eyes approval and publication; candidate result view; recomputation reproduces exactly | 2.5 | 0.5 | M3 | A | — | Day 6 (11-14) | — |
| M4b Results completion | Ranks and tie-breaks, normalization, scorecards with verification hash, queries and grievances, result analytics | 4 | 1 | M4 | C | — | Week 3 (11-27) | — |
| **Day-7 integration** | Journeys 1–4 on one build; load and chaos; security regression | — | 2 | all above | A | — | Day 7 (11-15) | Milestone |
| M5 Question bank completion | Blank, matching, ordering; secure banks; QTI; KI-75 | 5.5 | 1 | 2.1 | B | — | Week 3 (11-28) | KI-77/78 |
| M6 Admissions | Cycles, offered programs, schema-driven forms, public application portal, document upload (quarantine), eligibility rules, application review; merit lists, cut-offs, offers, acceptance, conversion to students | 6 | 1.5 | M4 | C then A | **DPDP notices** | Week 4 (12-04) | Counsel sign-off; RB-1 |
| M7 Manual evaluation | Essay and upload types, rubrics, anonymized allocation, double and third marking, moderation, re-evaluation | 4 | 1 | M4 | B | — | Week 4 (12-05) | — |
| M8 Academic assessments | Components per offering, weightage, grade computation, feed to student records | 2 | 0.5 | M7 | B | — | Week 5 (12-09) | — |
| M9 Proctoring L1 | Browser signals in attempts, device binding, flags for review | 1.5 | 0.5 | M3 | B | — | Week 3 (11-25) | — |
| M10 Proctoring L2/L3 | Snapshots, vision worker, flags with confidence, review queue, decisions by humans only, appeals, fairness report | 4 | 1 | M9 | C | **DPDP consent (biometric/video), model hosting** | Week 6 (12-18) if consent and model are ready by Week 4 | Gated on legal |
| M11 Coding assessments | ADR-0004 PoC P1–P8 on KVM → code-runner, language profiles, coding items, hidden tests, verdicts, similarity | 5 | 1 | M3, M7 | A | **KVM-capable nodes** | Week 6 (12-20) **only if KVM is available by Week 3** | Gated on infrastructure and pen test |
| M12 AI gateway and assistance | Providers per tenant, policies, templates, drafts, evaluation suggestions with quality gate, narratives; off by default; full invocation audit | 4 | 1 | M7 | C | **Provider contract and keys, DPDP** | Week 6 (12-19) if keys by Week 4 | Gated |
| M13 Placement | Companies, drives, eligibility, shortlists, recruiter portal, consent | 3 | 0.5 | M4 | B | DPDP | Week 5 (12-12) | — |
| M14 Institutional integrations | SSO brokering, LTI 1.3 launch and grade passback, OneRoster, new events | 3.5 | 1 | M4 | C | **Reference LMS and IdP** | Week 6 (12-19) | — |
| M15 Platform administration | Tenant provisioning, branding, bulk users, email templates, maintenance windows, licensing and metering | 4.5 | 0.5 | — | B | — | Week 5 (12-11) | — |
| M16 Compliance | RB-3 rotation tool, RB-4 account lifecycle, DPDP workflow (consent, requests, retention) | 7 | 1 | counsel input | C | **Counsel** | Week 6 (12-18) | Gated on counsel |
| M17 Deployment | Helm, production Keycloak, digest-pinned images, on-premises bundle, monitoring, DR drill support | 3 | 1 | DevOps | A | **DevOps, infrastructure** | Week 6 (12-20) | DR drill external |
| Reserves | Pen-test remediation, accessibility and device findings | 6 | — | external | all | external | Weeks 7–9 | — |

Effort totals (Eff. + Test/int.):
- Day-7 path: about 19 days of effort across three streams;
- whole table: about 72 engineering days.

This is consistent with §2.

## 4. Production-readiness path (external validation is never assumed)

| Target | Condition |
|---|---|
| **Day 7 (11-15)** | Integrated objective platform on synthetic data; CI green; load and chaos report; **no real candidates** |
| **Week 3–4 (by 12-06): conditional real-candidate pilot** | RB-1 scanner in staging verified; RB-2 key custody; production Keycloak realm; staging on target infrastructure with monitoring; KI-66/71–73 done; pen test of the objective path done and critical/high findings fixed; DPDP notices and consent for candidates approved; KI-77/78 manual results with blocking issues fixed; dress rehearsal with the institution |
| **Week 6 (12-20): full functional scope** | Every module in §3 implemented, integrated and CI-verified. M10, M11 and M12 depend on consent, KVM and the provider (§5). If those arrive late, those modules finish late by the same amount; they are not marked done |
| **Weeks 8–10 (12-28 to 01-17): production rollout** | Full pen test and retest; DR drill; RB-1 to RB-6 closed with evidence; counsel sign-off for proctoring, AI and minors; operational runbooks rehearsed |

## 5. External dependency and resource plan (D-5)

All items can start now, in parallel with development, unless marked.

| Owner | Exact work | Needed by | Parallel? |
|---|---|---|---|
| **DevOps** | (1) Staging environment on the production target (Kubernetes or VMs) with TLS domains for web, exam, API and identity. (2) **ClamAV** (clamd and freshclam) reachable from the API; run the RB-1 release test. (3) **Secret store** (Vault or cloud KMS) for `IVOROVI_DATA_KEY` and other secrets, escrow backup, recovery drill (RB-2). (4) Managed PostgreSQL with PITR backups, Valkey, RabbitMQ, object storage. (5) Production Keycloak realms from templates without the dev CLI client (SA-03); SMTP provider. (6) Monitoring and alerting (metrics, logs, uptime), on-call contact for exam days. (7) **KVM-capable node pool** for code execution (M11). (8) Deployment pipeline from CI to staging; image digests; DR drill (week 8) | (1)–(5) by Day 10 for the pilot path; (7) by week 3; (8) by week 7 | Yes |
| **QA tester** (accessibility and devices) | (1) NVDA (Windows), JAWS, VoiceOver (macOS and iOS), TalkBack (Android) on the exam app and the question and assessment screens: math rendering, question navigation, image descriptions, keyboard-only use, form errors (KI-77). (2) Real iPhone, iPad and Mac Safari (KI-78). (3) Exploratory testing of journeys 1–4 each day from Day 5. (4) UAT script and dress rehearsal with the institution | (1)–(2) from Day 5, findings by week 3; (4) week 4 | Yes |
| **Security tester** (external) | (1) Pen test of the objective path: staff app, exam app, delivery and staff APIs, Keycloak configuration, multi-tenant isolation, answer-key leakage, replay and idempotency, timing manipulation, upload handling. (2) DAST in CI. (3) Retest after fixes. (4) Second round for coding sandbox escape (M11), AI and proctoring | Booking now; test window week 3; retest week 4; full round weeks 7–8 | Yes (lead time) |
| **DPDP legal counsel** | (1) Review of notices, purposes, consent (including verifiable parental consent for minors), retention schedule, data-principal rights and statutory academic retention (RB-5, brief `legal/dpdp-review-brief.md`). (2) Proctoring: video and biometric processing, consent, retention. (3) AI processing and providers; cross-border transfer. (4) Processor agreements (cloud, email, AI). Outputs: approved texts and rules that M6, M10, M12 and M16 implement | (1) by week 3 for the pilot; (2)–(3) by week 4 for M10 and M12 | Yes |
| **You (product owner)** | Daily review (D-3); contract confirmation of §1; AI provider choice and contract; reference LMS sandbox; institution contact for the dress rehearsal | Ongoing; AI and LMS by week 4 | — |

## 6. Parallel execution governance (D-4)

| Topic | Rule |
|---|---|
| Ownership | **Revised 2026-11-09 (client instruction, Day 1 review §2).** **Session A (primary):** architecture, integration and acceptance, assessment authoring (M1) and examination delivery and scoring (M3, M4), final quality; owns `main`. **Session 2** (session `…r4b4D93yeXL9o3MAC`, branch prefix `ws-b/`): scheduling, candidate enrolment (bulk upload and invitation), examination configuration and extra time, invigilator workflows — new module `com.ivorovi.scheduling`. **Session 3** (session `…oYw3Yct98ZXZJrXg3`, branch prefix `ws-c/`): independent QA automation, security regression, performance and failure testing (tests and tooling only; KI-75 benchmark, KI-80 root cause, KI-81). The seven-day plan must not depend on three sessions running continuously: if a session is blocked or rate limited, Session A reports it the same day and re-plans; scope is never reduced silently. Work moved from the previous split (M5 question types and QTI; KI-66 and KI-71–73 outage work) returns to the backlog and stays in the contractual scope |
| Interfaces (Day 2) | Published on `main` by Session A: `com.ivorovi.assessments.PublishedAssessments` (published versions only, permanent references, candidate papers); `com.ivorovi.exams.ExamSessions` (exam sessions, single-use codes, check-ins, candidate lookup by keyed email index); `com.ivorovi.exams.ExamEligibility` (SPI implemented by `scheduling`, consumed by delivery); `com.ivorovi.questions.QuestionMarking` (scoring inside the question module). Session 2 builds only against these; a change to them is made by Session A |
| Module boundaries | One Spring Modulith module per area. A stream writes only in its modules plus their tests and UI routes. A cross-module change goes through Session A (the `ModularityTest` architecture test enforces the module dependencies) |
| Interface contracts | Session A publishes Java module APIs and OpenAPI paths first, as stubs that compile and throw "not implemented" (never fake data) and are documented in `docs/api/openapi.json`. Streams build against the contract; a contract change is a reviewed change by A |
| Branches and merges | `main` is protected by convention: only Session A merges and pushes `main`. Streams work on `ws-b/<module>` and `ws-c/<module>` branches, rebase on `main` daily, and push their branch (never `main`, never force-push to shared branches). A merge needs: the stream's own test run (backend tests of its modules plus the full suite before handover, frontend lint, typecheck and tests, the relevant E2E), and A's review (security checklist: tenant isolation, authorization, answer keys, audit, no fake data). A then runs the full CI on `main`. Pull requests are not used unless you ask for them (CI runs on `main` pushes). A stream's commits never touch another stream's modules |
| Migrations | One migration file per module change. Versions must sort after every migration on `main` (Day 2: latest `V202611141010`), so the version uses a **programme date**: day N of the programme is 2026-11-(12+N), e.g. Day 1 → `20261113`. Stream suffix: A `0xxx`, Session 2 (`ws-b/`) `1xxx`, Session 3 (`ws-c/`) `2xxx` (e.g. Day 1: A `V202611130010`, B `V202611131010`, C `V202611132010`). If two streams' files would interleave unexpectedly, Session A renumbers before merge, which is allowed only for unmerged files. Migrations are never edited after merge |
| Integration checkpoints | Daily at 16:00 UTC: A merges ready PRs, runs full CI (backend, frontend, E2E, security), deploys to the local or staging stack, runs journeys, and writes the daily report. A broken `main` is fixed or reverted by A before anything else merges |
| Single source of truth | Scope and dates: this baseline. Daily state: `../tracker.md` (updated by A at the checkpoint). Defects: `../known-issues.md`. Decisions: `decision-register.md`. Each session reads them at start; only A edits the baseline |
| Quality bar | Every stream: unit, integration (Testcontainers) and E2E tests, cross-tenant and authorization tests, audit for every action, no mocks or fake data presented as functionality, no secrets |

## 7. Daily report format (D-3)

Each report covers:
- completed modules;
- working end-to-end journeys (demo links);
- automated test results (backend, frontend, E2E, security) with the CI run;
- outstanding defects;
- security and production blockers;
- remaining work with effort (re-estimated);
- dependencies and decisions needed from you.

Published as a page each day, with a summary in the conversation.
