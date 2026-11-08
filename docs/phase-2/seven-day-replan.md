# Seven-day replan — gap analysis and execution plan (2026-11-08)

Requested by the product owner: deliver the complete agreed platform in seven days, reusing everything that exists.
This document is the first deliverable: an evidence-based inventory, a gap matrix, a critical-path plan, workstreams,
the Day-7 deliverables, risks and the resources needed. **No code has been written for it.**

**State of the working tree.** The KI-79 fix (approved earlier today) is written but not compiled, tested or committed
(5 files plus 1 new test), together with the MVP scope document and the Q-2.2 decisions. It is paused, because this
replan supersedes the MVP instruction.

## 0. Bottom line (brutally honest)

- **The complete agreed platform cannot be delivered in seven days.** Phase 2 was estimated at 45–55 engineering weeks
  for a team of 2–3. Increments 2.0 and 2.1 are done; 2.2–2.15 remain.
  - Several remaining parts cannot be finished by writing code in this repository at all. Each depends on
    infrastructure, external parties or people that this environment does not have:
    - secure code execution needs KVM-capable nodes (KI-15);
    - AI assistance needs a provider contract, keys and an evaluation gate;
    - proctoring vision needs models, consent and a fairness evaluation;
    - LTI and SSO need a reference LMS and an identity provider;
    - penetration testing, the DPDP legal review, a real malware scanner, key management and real-device and
      screen-reader testing are all outside the code base.
- **What is feasible in seven days, at the platform's established security bar, is the complete objective-examination
  pipeline.** It is the path the roadmap calls milestone M1:
  1. question bank (done);
  2. assessment authoring and publication;
  3. candidate enrolment into an examination;
  4. scheduling and admit details;
  5. secure online delivery: timing, autosave, resume, idempotent submission, auto-submission, invigilator controls;
  6. automatic scoring;
  7. result approval and publication;
  8. candidate result view;
  9. reports and dashboards.

  It would run end to end with synthetic candidates and be load-tested. That alone is aggressive. It needs daily
  decisions from you and no other work in parallel on the same code.
- **Not production use.** Even then the result is not production-authorized. The release blockers RB-1 to RB-6 need
  operations, security and legal work that runs alongside development and outside it.

Recommendation: commit to the client for Day 7 on **a working, demonstrable end-to-end objective-assessment platform
(synthetic data, staging-grade)**, plus a **dated plan for everything else**. Do not commit to "complete platform in
production" in seven days.

## 1. Platform feature inventory and status

Legend:
- **Done**: implemented, accepted, verified in CI with persistence, authorization and E2E tests.
- **Partial**: some of it exists; the gap is named.
- **Missing**: no implementation.

Evidence: acceptance records `phase-1/increment-1.x.md`, `phase-2/increment-2.0.md` and `increment-2.1.md`; last
full green CI run 42469900842 (407 backend tests, 115 web E2E, 8 exam-app E2E).

| Area | Capability | Status | Evidence or gap |
|---|---|---|---|
| Platform | Multi-tenancy with RLS, tenant context, isolation tests | Done | `TenantIsolationIT` (RLS on every table), all ITs |
| | Audit trail (hash-chained), audit screen, verification | Done | `AuditTrailIT`; KI-06 (O(n) verification) planned |
| | Idempotency, rate limiting, caching, outbox, job leases | Done | 1.0 and 2.0 records; KI-58 and KI-70 minor |
| | Field encryption | Done (code) | **RB-2/RB-3 open**: key custody and rotation (KI-49, KI-51) |
| | Malware scanning | Partial | Code and fail-closed logic done; **never run against a real ClamAV (RB-1, KI-23)** |
| | Observability | Partial | Correlation ids, health; no trace export (KI-09); no production monitoring stack |
| Organization | Hierarchy, institutions, settings, terminology | Done | 1.1 (`OrgUnitIT`) |
| | Controlled restructuring | Missing | Designed only (KI-45) |
| Users and access | Users, staff, invitations, assignments | Done | 1.2 |
| | Custom roles, ceilings, permission catalogue | Done | 1.3 |
| | Privileged roles with four-eyes assignment, operator bootstrap | Done | 2.1/P2-08 (`PrivilegedRoleAssignmentIT`) |
| | Four-eyes approval framework | Done | 2.0 (`ApprovalsIT`) |
| | Service identities, API clients, live authorization | Done | KI-59 (1.7) |
| Academics | Programs, courses, subjects, calendar, terms, batches, offerings, faculty | Done | 1.4 |
| | Grading, internal/semester component marks | Missing | 2.9 |
| Students | Lifecycle, admissions record, enrolment, guardians, documents, accounts | Done | 1.5; documents need RB-1; RB-4 (KI-46) open |
| | Bulk import and migration | Done | 1.6 (RB-1 for files) |
| | DPDP data-principal requests | Missing | RB-5 (KI-48); legal review not started |
| Dashboards, reports | Executive, academic, student, staff and import dashboards; reports with export | Done | 1.7 |
| | Assessment and result analytics | Missing | Need results (2.6) |
| Notifications | In-app, email with preferences and retries | Done | 1.7, 2.0 |
| Integrations | Webhooks, API keys, generic REST adapter | Done | 1.7 |
| | SSO brokering, LTI 1.3, OneRoster | Missing | 2.14; needs a reference LMS and an IdP |
| Candidates | Candidate realm, registration, MFA, recovery, session codes | Done (KI-79 defect) | 2.0; KI-79 fix written, untested |
| | Admission cycles, application forms, eligibility, application review | Missing | 2.3 |
| | Candidate records linked to students (conversion) | Missing | 2.7 |
| Question bank | Banks, governance, versions, taxonomy, search, import, images, math | Done | 2.1 (KI-75 to KI-78 open) |
| | Fill-in-the-blank, matching, ordering, essay, file upload, coding types | Missing | 2.1 delivered 5 objective types; others in 2.8 and 2.11 |
| | Secure banks (custodian-only) | Missing | `question.secure.read` planned |
| Assessments | Creation, purposes, configuration, selection, random rules, marks, validation, preview, review, publication, immutable versions | Missing | Plan and MVP spec written; decisions Q-2.2-1 to Q-2.2-6 recorded; no code |
| | Templates, sections, blueprint constraints | Missing | Deferred in the MVP spec |
| Scheduling | Events, sessions (fixed or window), venues, allocation, accommodations, admit cards | Missing | 2.4. The 2.0 *readiness sessions* (check-in rehearsal) exist and can be extended |
| Delivery | Exam application on its own origin, separate delivery process, CSP, candidate auth, check-in with codes, presence, clock-offset check | Done | 2.0 |
| | Durable acknowledged save (practice endpoint, load-tested at 2,000 clients, p95 39 ms) | Partial | Proves the persistence path; no real answers or papers yet |
| | Papers, attempt lifecycle, server timing, autosave journal, offline queue, resume, idempotent submit, auto-submit | Missing | 2.5; answer-persistence requirements written (`07-examination-delivery.md`) |
| | Invigilator dashboard (admit, monitor, extend, incidents) | Partial | Incident and compensation workflow done (2.0); no live monitoring |
| | Layered rate limits for shared campus addresses; outage detection beyond total failure | Partial | **KI-66, KI-71 to KI-73 required before real delivery** |
| Evaluation | Auto-scoring of objective types | Partial | Marking functions per type exist (2.1 `QuestionType.mark`, unit-tested); no attempt scoring pipeline |
| | Results computation, approval, publication, scorecards, candidate view | Missing | 2.6 |
| | Merit lists, cut-offs, offers | Missing | 2.7 |
| | Manual evaluation, rubrics, moderation, re-evaluation | Missing | 2.8 |
| Proctoring | L1 integrity signals (focus loss, device binding) | Partial | Device key and system check exist; no signal capture in an attempt |
| | L2/L3 snapshots, vision analysis, review queue, appeals | Missing | 2.10; needs consent (DPDP), models and a fairness evaluation |
| Coding | Secure code execution (sandbox), coding items, test cases | Missing | 2.11; **blocked: Kata/gVisor PoC needs KVM, which is not available (KI-15)** |
| AI | AI gateway, drafts, evaluation suggestions, narratives | Missing | 2.12; needs a provider decision, keys and a quality gate; off by default |
| Placement | Drives, recruiter portal, consent | Missing | 2.13 |
| Deployment and operations | Docker Compose, CI with security scans | Done | — |
| | Helm/Kubernetes, production Keycloak, digest-pinned images, on-premises bundle, DR drills | Missing | KI-14, 2.15 |
| | Penetration test or DAST | Missing | RB-6 (external) |
| Quality | Manual accessibility, real Apple devices | Missing | KI-77, KI-78 (people and devices) |

Count of the rows above: **Done 19 (two with qualifications: encryption key custody, KI-79), Partial 7, Missing 23.** Everything from scheduling onwards in the examination pipeline is
Missing or Partial.

## 2. Reusable foundations (not to be rebuilt)

- **Security:** tenancy, RLS, auth, live authorization, privileged roles, four-eyes framework, audit, outbox,
  idempotency, rate limits, job leases, field encryption.
- **Candidate side:** candidate realm with MFA and recovery; the separate delivery process with its CSP and boundary
  tests; the exam app shell (sign-in, system check, device key, clock offset, check-in, presence); session codes.
- **Durability evidence:** the practice save endpoint and the 2,000-client load and failure harness.
- **Questions:** question-bank governance pattern, the type plug-ins with deterministic marking functions, the delivery
  projection without keys, the content renderer (KaTeX), images.
- **Exam operations:** the incident and compensation workflow.
- **Staff UI and tooling:** UI kit, routes, guards, E2E harness, CI.

## 3. Seven-day critical-path plan (objective-examination pipeline, M1 path)

The critical path is assessment authoring → scheduling and allocation → delivery → scoring and results. Each day ends
with an integrated build: all tests green in CI, a demo-able journey, and a daily report.

| Day | Critical path (engineering) | Integrated result at end of day |
|---|---|---|
| 1 | KI-79 fix verified. Assessments: data model, DB guards, approved-question API, paper generator (secure seed, stored papers), validation | Assessment created and validated via API; tests green |
| 2 | Assessment workflow (review, three-person publication, immutable versions, references) and UI (list, editor, picker, random rules, preview, workflow) | **Journey 1:** author → review → publish an admission test in the UI |
| 3 | Scheduling: examination events and sessions (fixed and window) on the 2.0 readiness sessions; candidate enrolment (bulk CSV of candidates or invitations into an event); allocation; admit details and session codes; accommodations (extra time) | **Journey 2:** schedule a session, enrol 2,000 synthetic candidates, issue codes |
| 4 | Delivery backend: attempt lifecycle, one sealed paper per attempt (Q-2.2-4), server deadline, autosave journal (append-only, acknowledged only when durable), resume, idempotent submit, auto-submit at deadline, no keys in any delivery payload | Attempt API with persistence and chaos tests |
| 5 | Exam app UI: instructions, question navigation, timer from server time, autosave with an offline queue (never shown as saved until acknowledged), resume, submit. Invigilator view: admit, monitor progress, extend time, incidents (reuses 2.0) | **Journey 3:** candidate takes, loses connection, resumes and submits; invigilator extends time |
| 6 | Scoring and results: deterministic scoring from sealed papers and responses (2.1 marking functions), result sets, four-eyes approval and publication, candidate result view, results report and dashboard tile; work package R minimum (layered rate limits KI-66, per-instance outage detection KI-71) | **Journey 4:** results computed, approved by a second person, published, seen by the candidate |
| 7 | Load and chaos gate (2,000 candidates, zero lost acknowledged answers, p95 save < 300 ms), security regression (answer-key leakage, cross-tenant, replay and idempotency, time manipulation), full E2E, docs, demo, report | **Full journey 1–4 on one build**; CI green; demo; honest gap report |

Explicitly **not** on the 7-day path:
- admission cycle with application forms and eligibility (2.3 full), merit lists and offers (2.7);
- manual evaluation (2.8), academic grading (2.9);
- proctoring L2/L3 (2.10), coding (2.11), AI (2.12), placement (2.13), LTI/SSO (2.14);
- Helm, on-premises and DR (2.15).

These each need their own dated plan (§7).

## 4. Workstreams and owners

There is one engineering agent (this session), and it works sequentially. Parallel development by several agents on
one repository and one migration sequence is possible, but it adds integration risk. I recommend it only for
independent areas (marked ✓ below), with this session owning integration, migrations and security review.

| Workstream | Scope | Owner | Parallel? |
|---|---|---|---|
| WS-1 Critical path | Days 1–7 above | Claude (this session) | — |
| WS-2 Exam-app UI | Day 5 UI, against the Day-4 API contract | Second Claude session (if you approve) | ✓ after Day 4 contract |
| WS-3 Hardening R | KI-66 layered rate limits, KI-71 to KI-73 outage detection | Second or third Claude session | ✓ (separate files) |
| WS-4 Operations | Staging environment, real ClamAV (RB-1), secret store and key escrow (RB-2), monitoring, Helm (KI-14) | **Human DevOps engineer** (needs infrastructure access I do not have) | ✓ |
| WS-5 Security | Penetration test or DAST booking and execution (RB-6) | **External security tester** | ✓ (lead time is usually weeks) |
| WS-6 Legal and privacy | DPDP review, notices, consent incl. minors, retention (RB-5) | **Counsel**, engaged by you | ✓ |
| WS-7 Accessibility and devices | NVDA, JAWS, VoiceOver, TalkBack; iPhone, iPad, Mac Safari (KI-77, KI-78) | **Human QA tester with devices** | ✓ from Day 5 |
| WS-8 Product decisions | Daily review and same-day decisions; client scope confirmation | **You** | — |

## 5. Exact client-facing deliverables by Day 7 (if this plan is approved today)

1. Staff web application with organization, people, roles, academics, students, imports, dashboards, reports,
   notifications, integrations, audit and question bank (all existing), plus **assessment authoring and publication**,
   **examination scheduling and candidate enrolment**, **invigilator controls**, and **result approval and
   publication**.
2. Candidate exam application: registration and MFA (existing), check-in with codes (existing), plus **taking a
   published assessment** with server timing, autosave, resume, submission, auto-submission and the **result view**.
3. One integrated build: CI green; E2E journeys 1–4; load and chaos report (2,000 synthetic candidates); security
   regression report.
4. Demonstration script and recording on synthetic data; operator runbooks for the exam day.
5. Gap register: every capability not delivered, with a dated plan, and the open release blockers.

**Not deliverable by Day 7:** production authorization; the items in §3 "not on the path"; RB-1 to RB-6 closure, which
depends on WS-4 to WS-6.

## 6. Risks that could prevent even the Day-7 scope

| Risk | Effect | Mitigation |
|---|---|---|
| Review and decision latency (the established per-increment acceptance gates) | Each gate can cost a day | Daily review instead of per-increment gates; decisions within hours |
| Environment instability (container restarts, Docker daemon loss; seen repeatedly) | Lost hours | Commit at every green state; scripted recovery |
| CI cycle time (about 15 minutes per run; E2E-heavy) | Throughput | Batch pushes; local verification first |
| Durability gate failure (zero lost answers under chaos) | Blocks delivery sign-off | Reuse the 2.0 save path and harness; test on Day 4, not Day 7 |
| Scope additions mid-week | Critical path slips | Change control through you; anything new goes after Day 7 |
| Real infrastructure absent (staging, ClamAV, KMS) | No production evidence | WS-4 human owner from Day 1 |
| "Agreed scope" unclear (whether AI, proctoring and coding are contractual) | Mis-set client expectations | Confirm the contract scope list today (decision D-1) |

## 7. Everything else: realistic timing after Day 7 (same quality bar)

| Block | Content | Earliest, with one engineering agent | With parallel capacity (2–3 streams plus WS-4) |
|---|---|---|---|
| Admissions | 2.3 full (cycles, forms, eligibility, review) + 2.7 (merit lists, offers, conversion) | +2–3 weeks | +1–1.5 weeks |
| Evaluation | 2.8 manual evaluation and moderation + 2.9 academic grading | +2–3 weeks | +1–1.5 weeks |
| Proctoring | 2.10 L2/L3 (needs consent, legal review, model choice) | +3 weeks after legal sign-off | +2 weeks after legal sign-off |
| Coding | 2.11 (needs KVM-capable infrastructure first) | +3–4 weeks after infrastructure | +2–3 weeks |
| AI | 2.12 (needs provider and keys) | +2–3 weeks | +1.5–2 weeks |
| Placement | 2.13 | +2 weeks | +1 week |
| Integrations | 2.14 (needs a reference LMS and IdP) | +2–3 weeks | +1.5 weeks |
| Operations | 2.15 + RB closure (pen test, DR drill, on-premises bundle) | depends on WS-4 to WS-6 | 3–6 weeks of external lead time |

These ranges are better than the original roadmap because increments 2.0 and 2.1 were delivered much faster than
estimated. They are still estimates; each block is re-estimated against its design.

## 8. Minimum additional resources

1. **You, daily**: a 30-minute review and same-day decisions; confirmation today of the contractual scope list (D-1).
2. **One DevOps engineer** (part-time from Day 1): staging environment, ClamAV, secret store and key escrow,
   monitoring. Later, KVM-capable nodes for coding and Helm.
3. **One QA tester** with screen readers and Apple devices (from Day 5).
4. **Security tester booking** now, because of lead time.
5. **Legal counsel** for DPDP, now.
6. Optional: approval to run **one or two additional Claude engineering sessions** for the independent workstreams
   (WS-2, WS-3), with this session as integrator.
7. If AI, proctoring or coding are contractual: a provider account and keys (AI); infrastructure with KVM (coding).

## 9. Decisions required from you now

| # | Decision |
|---|---|
| D-1 | Confirm the contractual scope list (is AI, proctoring, coding or placement contractual for this client, and for when?) |
| D-2 | Approve the Day-7 commitment as defined in §5 (end-to-end objective platform, synthetic data, not production) |
| D-3 | Replace per-increment acceptance with daily review for this week (security requirements unchanged) |
| D-4 | Approve additional parallel Claude sessions for WS-2 and WS-3 (optional) |
| D-5 | Name the human owners for WS-4 to WS-7 |
| D-6 | Candidate enrolment for the Day-7 path: bulk upload or invitation of candidates into an examination event, with the full admission cycle (forms, eligibility) after Day 7. Confirm or amend |
