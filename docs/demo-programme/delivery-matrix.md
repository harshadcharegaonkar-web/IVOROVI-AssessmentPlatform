# Consolidated delivery matrix: 18 modules (Day 1, 2026-11-16)

**Sources:**
- the merged repository at `c87935a` (CI green on `0dd101b`);
- the assessment in `day-1-assessment.md`;
- the approved allocation (D-18).

**Notes:**
- Estimates are one-agent days, including tests, to **demo acceptance** (§7 of the assessment).
- The deferred contractual work stays in scope (`delivery-baseline.md`).
- **No module counts as accepted until its workflow passes the acceptance criteria on the demo environment.** Today, only the mandatory modules have working workflows.

## The matrix

| # | Module | Owner | Existing functionality (verified) | Missing work for demo acceptance | Est. (days) | Dependencies | Confidence |
|---|---|---|---|---|---|---|---|
| M-A | Organization and institution management | Session 2 | Hierarchy, units, settings, terminology, restructuring design | Demo institution data | 0.5 | Session 3 dataset | **High** |
| M-B | User, role and access management | Session 2 | Users, staff, invitations, custom roles, ceilings, privileged four-eyes assignment, bootstrap | Demo accounts and roles | 0.5 | Session 3 dataset | **High** |
| M-C | Academic structure and student management | Session 2 | Programmes, courses, subjects, calendar, batches, offerings; students with lifecycle, documents, accounts, bulk import | Demo cohorts and histories | 0.5 | Session 3 dataset | **High** |
| M-D | Question bank management | Session A | Objective types, governance, review and approval, mathematics, images, import, answer-key isolation | Descriptive (essay and short-answer) type, with rubric attachment | 1.5 | — | **High** |
| M-E | Assessment creation and management | Session A (core), Session 4 (sections) | Rules over pools, frozen pools, per-candidate papers, validation, preview, three-person publication, immutable versions | Sections (by Session 4, add-on C) | 1 | Session 4 | **High** |
| M-F | Examination scheduling and delivery | Session A | Scheduling, bulk enrolment, invitations, codes, candidate accounts and MFA, check-in protection, delivery with autosave, resume, crash recovery, timer, idempotent submission, auto-submit, invigilation, extra time, termination | Admit card (PDF, after the OpenPDF licence check) | 1 | OpenPDF verification | **High** |
| M-G | Evaluation, scoring and results | Session A | Automatic scoring; independent review, approval and publication (R-1) | Manual evaluation (anonymized marker allocation, rubrics, double marking, moderation, re-evaluation request); ranks; scorecard | 7 | M-D descriptive type | **Medium** (largest new mandatory workflow) |
| M-H | Dashboards, reports and notifications | Session A | Institutional dashboards, reports, in-app and e-mail notifications | Assessment and result analytics (item analysis, distributions); result notifications | 2 | M-G | **High** |
| M-I | Security and platform administration | Session 2 | Audit trail, feature flags, system settings, rate limits, encryption key ring | Security-policy screen (MFA, session, password policy); maintenance windows | 1.5 | — | **High** |
| AO-A | AI-powered assessment | Session 4 | Feature flags and permissions only | Provider gateway (per tenant, off by default, every call audited, no personal data sent); question generation and enhancement into drafts through the existing review; AI-assisted evaluation suggestions accepted or changed by a marker | 6 | **AI provider key: NOT AVAILABLE (D-20)**; M-G | **Cannot be accepted until a key is configured.** Build: medium |
| AO-B | Advanced proctoring and examination security | Session 4 | Invigilation monitor, connection interruptions, incidents, four-eyes termination | Consent; browser signals; webcam snapshots as evidence; flags; invigilator review queue and decision | 6 | DPDP consent text (draft until counsel); optional face-detection model hosting | **Medium** |
| AO-C | Advanced assessment and examination capabilities | Session 4 | Pools, random rules, per-candidate selection | Sections with per-section pools; blueprint constraints; fill-in-the-blank, matching and ordering types end to end | 7 | Contracts with Session A | **Medium** (shared modules) |
| AO-D | Coding and technical skill assessments | Session 2 (sixth agent proposed) | Feature flag only; ADR-0004 design | Runner service (gVisor or Kata; PoC P1–P8); coding question type; CodeMirror editor; submission, execution, test cases, verdicts, scoring | 8 | **Execution host from DevOps by Day 3**; CodeMirror (approved) | **Low** until the host is available |
| AO-E | Admission management and enrollment automation | Session 5 | Admission *records* of existing students; documents with quarantine; student conversion | Cycles; application portal; documents through ClamAV; eligibility; screening and shortlisting (entrance test on the exam engine); merit list; offers; conversion | 7.5 | ClamAV in the demo stack (approved); DPDP notice text (draft) | **Medium** |
| AO-F | Academic performance and learning analytics | Session 5 | Institutional dashboards | Learning outcomes; question-to-outcome tagging; attainment; student, subject and cohort analysis; at-risk list | 5 | M-G results; M-D tagging | **Medium** |
| AO-G | Placement and recruitment assessment | Session 5 | Feature flag and permissions only | Employers; drives; eligibility; candidate profiles; aptitude and technical tests on the exam engine; ranking; consented employer view | 5 | AO-D for coding tests | **Medium** |
| AO-H | Integrations and enterprise capabilities | Session 3 | API clients, service identities, webhooks, REST adapter | SSO brokering with a demo identity provider; LTI 1.3 with Moodle (launch and grade return); webhook consumer demo | 5 | Moodle container (approved) | **Medium** |
| AO-I | Advanced enterprise and operational capabilities | Session 2 | Flags, settings, audit, restore runbook | Tenant provisioning console; branding; access administration; monitoring; backup and restore drill; deployment configuration | 7 | Prometheus and Grafana (approved); demo hosting | **Medium** |
| — | Demo data, demo environment, QA and security regression, demo script | Session 3 | Demo-data script for the platform foundation; CI | Interconnected dataset across all 18; reset; backup environment; credential register; QA evidence; rehearsals | 13 | **Demo hosting by Day 9**; every stream's data | **Medium** |
| — | Technical lead: contracts, reviews, merges, integration | Session A | — | Twice-daily integration and regression | 5 | — | — |

**Total: ≈ 90 one-agent days.** Capacity is about 80–85 with five agents over Days 3–10.

## Capacity and blocked dependencies, reported today

| Item | Status |
|---|---|
| Sessions 2 and 4 | **2–3 days over capacity each**. A sixth agent for coding is recommended (`day-1-assessment.md` §4); otherwise this is a shortfall on the critical path |
| AI provider key | **Not available.** AO-A cannot be accepted until it is. Needed by Day 3 to keep the schedule |
| Code-execution host | DevOps, by Day 3 |
| Demo hosting environment | By Day 9 |

## Delivery confidence (all 18 modules accepted by Day 14)

| Scope | Confidence |
|---|---|
| Overall | **Low to medium**, driven by the AI key, the execution host and the capacity gap |
| Mandatory nine | High |
| AO-B, AO-C, AO-E, AO-F, AO-G, AO-H, AO-I | Medium |
| AO-A and AO-D | Low until their dependencies arrive |

The matrix is updated in every daily report.
