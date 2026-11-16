# 14-day client demonstration programme — Days 1–2 initial assessment (2026-11-16)

Prepared by Session A (technical lead) from the merged repository at `f4dbca1`: CI run 43803503040, all jobs green.
**Decisions of 2026-11-16 (D-18 to D-20):** five agents approved and started; components approved with conditions; **AI provider not yet available** (the AI add-on cannot be accepted until a key is configured); code-execution host from DevOps by Day 3. Charter: `workstreams.md`.

## 1. Verdict

**All 18 modules with genuine, end-to-end workflows in 14 calendar days is not assured.**
- Without the infrastructure in §5 it is **not achievable** for two add-ons:
  - Coding (no isolated execution host);
  - AI (no model provider).
- It is **achievable at high risk** for the rest, if:
  - five agents work in parallel from Day 3;
  - the infrastructure and approvals in §5–§6 arrive by Day 3;
  - nothing else slips.

| Measure | Value |
|---|---|
| Estimated work to demo acceptance | **≈ 91 one-agent days** (§3) |
| Build capacity, five agents, Days 3–10 | **≈ 75–85 one-agent days**, at observed throughput less integration and review overhead (the parallel sessions also hit usage limits this week, which stops them for hours) |

**Gap:** about 6–16 days. To close it, any of:
1. Accept a narrower *demo depth* for some add-ons (listed in §3 as "reduced depth if needed"). This is not static screens: every workflow still has to work.
2. Add capacity: a sixth agent, or human developers.
3. Extend to 16–17 days.

**Confidence today:**

| Group | Confidence | Status |
|---|---|---|
| The 9 mandatory modules | High | All have working workflows already |
| 5 add-ons: proctoring, advanced assessment, admission, analytics, placement | Medium | Build from scratch, no external dependency |
| 4 add-ons: AI, coding, integrations (SSO and external system), enterprise | Low until §5 is resolved | Depend on infrastructure, providers or a deployment target |

Nothing here weakens the remaining contractual scope or the production gates. A successful demonstration is **not**
production readiness. RB-1 to RB-6 and P-1 to P-7 stay open.

## 2. What exists today (verified in the repository)

Already in place:
- 15 backend modules, about 37,500 lines of Java, 37 schema migrations;
- staff app and exam app;
- 530 backend tests, 143 browser E2E tests in CI.

Workflows that work through UI and backend, with permissions and tenant isolation:
- Organizations, hierarchy, settings.
- Users, staff, invitations, roles, permissions, privileged four-eyes assignment.
- Academic structure; students (lifecycle, documents, accounts, bulk import).
- Question bank (objective types, governance, mathematics, images, import).
- Assessment authoring: rules over pools, frozen pools, candidate-specific papers by seed, three-person publication.
- Scheduling, enrolment, invitations, session codes.
- Candidate accounts with MFA; delivery (autosave, resume, crash recovery, offline, idempotent submission, auto-submit).
- Invigilation (monitor, incidents, extra time, termination).
- Automatic scoring; independent result approval and publication.
- Dashboards and reports (institutional), notifications (in-app and e-mail).
- API clients, service identities, webhooks, a generic REST integration adapter.
- Audit trail; feature flags; system settings.

**The add-ons do not exist as workflows.** They exist only as feature-flag and permission names (`module.proctoring`,
`ai.use`, `assessment.type.coding`, `module.admissions`, `module.placement`, `integration.lti`). Under your acceptance
rule these count for nothing.

## 3. The 18 modules: gap to demo acceptance

Notes on the estimates:
- Estimates are **one-agent days including tests at the existing bar**: unit, integration, browser E2E, security regression.
- "Deferred" lists contractual work not needed for the demo. It stays in scope (`delivery-baseline.md`).

### Mandatory modules

| Module | Works today | Missing for demo acceptance | Est. | Deferred contractual work (unchanged) | Owner |
|---|---|---|---|---|---|
| A Organization and institution | Yes | Demo-institution data only | 0.5 | Tenant provisioning console, branding (see add-on I) | WS1 |
| B User, role and access | Yes | Demo accounts and roles | 0.5 | Bulk user import | WS1 |
| C Academic structure and students | Yes | Demo cohorts, history, documents | 0.5 | Student account lifecycle (RB-4) | WS1 |
| D Question bank | Yes (objective) | **Descriptive (essay/short-answer) type**, needed by G and AI evaluation | 1.5 | Fill-in-the-blank, matching, ordering, secure banks, QTI (see add-on C) | WS2 |
| E Assessment creation | Yes | Sections (needed by add-on C) | 1 | Templates, blueprint constraints | WS2 |
| F Scheduling and delivery | Yes | Admit card (PDF, needs library approval §6) | 1 | Venues, rooms, seats; M3c hardening | WS2 |
| G Evaluation, scoring, results | Automatic scoring and approval only | **Manual evaluation:** marker allocation (anonymized), rubric, double marking, moderation, re-evaluation request; **results:** ranks, scorecard | 7 | Normalization, grievances workflow depth | WS2 |
| H Dashboards, reports, notifications | Institutional only | Assessment and result analytics (item analysis, distributions), result notifications | 2 | — | WS2 |
| I Security and platform administration | Mostly | Security-policy screen (MFA, session, password policy), maintenance windows | 1.5 | — | WS1 |
| **Subtotal** | | | **15.5** | | |

### Add-on modules

| Module | Works today | Workflow to build for acceptance | Est. | Reduced depth if needed | External dependency | Owner |
|---|---|---|---|---|---|---|
| A AI-powered assessment | No | Provider gateway (per-tenant, off by default, every call audited, no candidate personal data sent); question generation and enhancement into **draft** questions that go through the existing human review and approval; AI-assisted evaluation suggestions in manual evaluation (G) that a marker must accept or change | 6 | Generation and evaluation only (enhancement later): −1.5 | **AI provider contract and API key** (§5) | WS3 |
| B Advanced proctoring | No | Candidate consent; browser signals (focus loss, tab or app switch, fullscreen exit, copy/paste, multiple displays where detectable); periodic webcam snapshots stored as evidence (tenant-scoped, retention-limited); flags with severity; invigilator review queue with decision (dismiss, warn, incident, terminate request using the existing four-eyes); candidate-visible notice | 6 | Without automated face detection (needs a hosted model): −1.5 | Face detection model hosting (optional); DPDP consent text (P-4) | WS3 |
| C Advanced assessment | Partly (pools, random rules, per-candidate papers exist) | Sections with per-section pools and rules; blueprint constraints (topic and difficulty coverage); new types (fill-in-the-blank, matching, ordering) end to end (authoring, delivery, scoring) | 7 | Two new types instead of three: −1.5 | — | WS3 |
| D Coding and technical | No | Runner service per ADR-0004 (gVisor or Kata; never in the application server); PoC gates P1–P8; coding question type with hidden and visible test cases; browser editor; submit, run, verdicts, partial scoring; plagiarism similarity (simple) | 8 | Two languages (Python, Java), no similarity: −2 | **An execution host with gVisor or KVM** (§5); editor library approval (§6) | WS3 |
| E Admission and enrollment | Student admission *records* only | Admission cycles and programmes on offer; public application portal (form, documents through the quarantine and ClamAV path); eligibility rules; screening and shortlisting (with an entrance test through the existing exam engine); merit list; offer, acceptance; conversion to student | 7.5 | Without the entrance-test link: −1 | ClamAV in the demo environment (P-1 container); DPDP notice text | WS4 |
| F Academic performance and learning analytics | Institutional dashboards | Learning outcomes per subject; question-to-outcome tagging; attainment from results; student, subject and cohort analysis; at-risk list | 5 | Without the at-risk list: −1 | — | WS4 |
| G Placement and recruitment | No | Employers, drives, eligibility; candidate profiles (from students); aptitude and technical assessments through the exam engine (and coding, D); ranking and shortlist; employer view with consent | 5 | Technical test without coding if D slips: −0 (depends on D) | — | WS4 |
| H Integrations and enterprise | API clients, webhooks, REST adapter | SSO: Keycloak brokering to an institutional identity provider (OIDC or SAML), with a demo identity provider; at least one **end-to-end external integration** (proposed: LTI 1.3 launch and grade return with Moodle, or a student-information-system sync); demo webhook consumer | 5 | One external integration only: already assumed | **Choice of external system** (§6); a Moodle container for LTI (licence GPL, separate service) | WS5 |
| I Advanced enterprise and operations | Feature flags, settings, audit | Tenant provisioning console (platform operator); branding (logo through the scan path, colours, e-mail templates); access administration views; monitoring (Prometheus and Grafana); backup and recovery (automated dump, restore drill on the demo environment); deployment configuration (Compose profiles plus Helm chart skeleton) | 7 | Helm later: −1 | Monitoring and backup containers (approval §6); a demo hosting target | WS5 |
| **Subtotal** | | | **56.5** | **≈ −9.5** | | |

### Cross-cutting (WS5 and the technical lead)

| Item | Est. |
|---|---|
| Interconnected synthetic demo dataset (one demo institution, every module; generator plus reset script) | 4 |
| Demo environment: deployment, reset in minutes, backup environment, credential register | 3 |
| Integration, merge review, API contracts, schema conflicts (technical lead) | 5 |
| Security regression for every new module: tenant isolation, authorization, sandbox isolation, AI data boundaries | 4 |
| 60-minute demo script and three rehearsals | 2 |
| **Subtotal** | **18** |

**Total: ≈ 90 one-agent days** (≈ 80 with every reduced depth applied).

## 4. Workstreams and owners (approved, D-18)

| Session | Workstream | Modules | Load (one-agent days) | Confidence |
|---|---|---|---|---|
| **A** | Technical lead + Examination | Mandatory D, E (descriptive type only), F, G, H; lead work | 11.5 + lead 5 = 16.5 | High for D, F, H; medium for G (largest new workflow) |
| **2** | Core, enterprise, coding | Mandatory A, B, C, I; add-on I; add-on D | 4.5 + 7 + 8 = 19.5 | High for A–C and I; medium for enterprise I; **low for coding until the host arrives (Day 3)** |
| **3** | Integrations, demo data, QA, deployment | Add-on H; dataset; demo environment; QA and security regression; demo script | 5 + 13 = 18 | Medium (depends on the hosting environment by Day 9 and on every stream's data) |
| **4** | AI, proctoring, advanced assessment | Add-ons A, B, C (C includes sections, moved from A) | 6 + 6 + 7 = 19 | Proctoring and advanced assessment: medium. **AI: cannot be accepted without a provider key** |
| **5** | Admission, analytics, placement | Add-ons E, F, G | 7.5 + 5 + 5 = 17.5 | Medium |

Capacity per session for Days 3–10 is about 16–17 one-agent days at the observed rate, net of integration and review.
**Sessions 2 and 4 are over capacity by 2–3 days each**, so the shortfall is visible on the critical path now.

### Should a sixth agent take coding?

**Recommended: yes, from Day 3.** Coding (add-on D, about 8 days) is the most independently assignable work in the
programme:
- the runner is a **separate service** behind an interface (ADR-0004);
- the coding question type and the editor are a new module;
- the only shared touch points are the question type registry, the exam app's answer component and the scoring hook, which A
  defines as contracts on Day 2.

Moving coding out of Session 2 brings Session 2 down to about 11.5 days. That frees Session 2 to absorb Session 4's
overflow, for example the fill-in-the-blank, matching and ordering types. It also removes the single largest
infrastructure-dependent risk from a stream that also carries enterprise work.

Conditions:
- the code-execution host is available by Day 3, otherwise the sixth agent has nothing it can accept;
- the agent works only in the `coding` module and the runner service, through A's contracts.

Cost: one more session's usage for about 8 working days.

## 5. Infrastructure and external services: what we need and by when

| Need | For | Without it | Needed by |
|---|---|---|---|
| **A Linux host for code execution with gVisor (`runsc`) or KVM** (2 vCPU / 4 GB is enough for the demo). This container has neither and cannot install a runtime. | Coding (D) | **Coding cannot be genuinely isolated → not acceptable; not demonstrable** | Day 3 |
| **AI provider contract and API key** for the product (not this development session's credentials). Provider choice is yours: Anthropic, OpenAI, Azure OpenAI or another. | AI (A) | **AI cannot be operational → not acceptable** | Day 3 |
| **A demo hosting environment** (one VM, 8 vCPU / 32 GB, Docker, a public TLS domain or VPN), plus a second as backup | Demo, rehearsals, backup environment | Demo only from a developer machine: fragile | Day 9 |
| An external system for the integration: Moodle (container we run) for LTI 1.3, or the client's own SIS or LMS sandbox | Integrations (H) | Only the container option | Day 5 |
| An institutional identity provider for SSO: we can run a second Keycloak as the "university IdP", or the client's own test tenant | Integrations (H) | Our own demo IdP only | Day 5 |
| DPDP consent and notice texts for proctoring video and admission (P-4) | Proctoring, admission | Our draft texts, labelled as drafts | Day 8 |

## 6. Decisions needed now

1. **Capacity:** approve five agents (two new sessions) now. Optionally a sixth for coding.
2. **AI provider and key** (§5).
3. **Code-execution host** (§5).
4. **Library and component approvals** (no unapproved libraries are added without this):
   - a code editor component (CodeMirror 6, MIT);
   - a PDF generator for admit cards and scorecards (OpenPDF, LGPL/MPL);
   - Prometheus and Grafana containers;
   - the ClamAV container in the demo stack;
   - a Moodle container for LTI (GPL, separate service).
5. **External integration choice:** LTI 1.3 with Moodle (recommended), or the client's system.
6. **Which reduced depths you accept** if the gap remains on Day 5 (§3).

## 7. Acceptance criteria (per module, applied by the technical lead and verified by WS5)

A module is accepted only when **all** of these hold:
- its workflow (as listed in §3) works through the UI and the backend on the demo environment, from the
  pre-populated data, with no setup during the demonstration;
- an automated browser E2E test drives that workflow, and backend integration tests cover its rules;
- its permissions are checked: who can and who cannot; four-eyes where decisions are consequential;
- its tenant boundaries are checked: another organization sees and changes nothing;
- it is audited without personal data or secrets;
- it is demonstrated three times in rehearsal, from a fresh reset;
- for AI: every call audited; no candidate personal data leaves the platform; nothing is applied without a human decision;
- for proctoring: no automated decision; consent before capture; evidence retention limited;
- for coding: execution isolated per ADR-0004, and the isolation tests (escape, network, resource exhaustion) pass.

## 8. Integration risks

| Risk | Mitigation |
|---|---|
| Five branches changing shared tables (questions, assessments, results) at once | Lead-owned API contracts; schema changes only in a branch's own module; one migration number range per workstream |
| Duplicate engines (a second exam engine for admissions or placement) | Admissions entrance tests and placement tests reuse the existing examination engine; reviewed at merge |
| The descriptive type and manual evaluation (G) block AI evaluation (A) | G is first in WS2's order; AI evaluation is built against G's interface |
| The coding sandbox is late | Its runner is an isolated service behind an interface; the rest of the platform does not wait for it |
| Parallel sessions stopping on usage limits | Daily checkpoints pushed to branches; work reassigned when a session stalls |
| Machine restarts in development environments (several this week) | Reset scripts; nothing kept only in a running process |

## 9. Day plan (subject to §6)

| Days | Work |
|---|---|
| 1–2 | This assessment; your decisions; contracts and data model per workstream; demo dataset design |
| 3–8 | Parallel build. Daily merges of finished workflows into `main` after review |
| 9–10 | Integration; the demo environment deployed; all 18 modules together; demo data populated |
| 11–12 | Full regression (backend, frontend, both browser suites, module acceptance, tenant isolation, security, sandbox isolation, AI and proctoring workflow tests, backup and restore) |
| 12–14 | Demo preparation, credential register, reset scripts, backup environment, three rehearsals |

A daily report covers: each workstream's work, commits and CI, test evidence, modules accepted and incomplete,
critical defects, infrastructure dependencies, remaining person-days, forecast and confidence.
