# Production dependency tracker (Day 5, 2026-11-15)

These are the external gates that must pass before any production use with real candidates. **The platform is not
production-ready** until every item is closed with evidence. Each item maps to the release-blocker register in
`production-readiness.md` (RB-n).

**Owners.** Owners are given as roles. The **product owner names the person or vendor** for each role; until then the
field reads "to be named". **Target dates** are proposals built on the delivery baseline's pilot milestone (week 3–4, by
2026-12-06); the product owner confirms them. Engineering's own share of each item is listed separately, so a delay can
be traced to the right party.

| # | Dependency | RB | External owner (role) | Engineering share (ready?) | Evidence to close | Proposed target | Status |
|---|---|---|---|---|---|---|---|
| P-1 | **ClamAV deployment and real malware-scan validation** | RB-1 | DevOps lead (staging and production clamd with signature updates) — to be named | Ready: clamd adapter (`ClamAvScanner`), fail-closed upload refusal without a scanner, quarantine, re-encoding; release test defined | clamd running in staging with updates; EICAR document, import file and question image refused (`INFECTED`); scanner timeout → refused (`ERROR`/503); nothing served before `CLEAN` | 2026-11-24 | Open — environment not provisioned |
| P-2 | **Encryption-key custody, backup and recovery** | RB-2, RB-3 | DevOps lead + security officer (secret store, escrow) — to be named | Partly ready: key loading, field encryption and runbook (`data-encryption-key.md`); **rotation tool not built (RB-3, about 1 day)** | Key in the production secret store; escrow copy; recovery drill in staging recorded; rotation rehearsed | 2026-11-27 (custody), 2026-12-04 (rotation) | Open |
| P-3 | **External penetration test and remediation** | RB-6 | External security tester (vendor) — to be named; product owner contracts | Ready to support: test accounts, synthetic organization, scope document; production realm template without the dev password client (SA-03) and unprivileged web image (SA-04) still to do (about 0.5 day) | Report; critical and high findings fixed and retested; residual risks accepted in writing | Objective path: test 2026-11-30, fixes by 2026-12-04; full test before production rollout | Open — vendor not engaged |
| P-4 | **DPDP legal review** | RB-5 | Legal counsel — to be engaged by the product owner | Ready: review brief (`legal/dpdp-review-brief.md`); consent and data-principal workflow designed, **not built (M16)** | Counsel's written review of notices, consent, purposes and retention; candidate notices approved; request workflow built and tested | Candidate notices 2026-12-04; full review before production | Open — counsel not engaged |
| P-5 | **Real-device Safari validation** | KI-78 | QA with physical devices (iPhone, iPad, Mac; Safari current and current − 1) — to be named | Ready: checklist, WebKit runs in CI (Playwright WebKit is not real-device Safari) | Manual run of the candidate journey and the maths rendering on each device, results recorded, blocking issues fixed | 2026-11-27 | Open — no devices in this environment |
| P-6 | **Screen-reader accessibility testing** | KI-77 | Accessibility tester (NVDA/JAWS on Windows, VoiceOver on macOS and iOS, TalkBack) — to be named | Ready: automated axe (WCAG 2.1 AA rules) passes in every E2E run; manual test script to be written (about 0.25 day) | Manual pass of the candidate journey and the key staff screens with each reader; findings fixed or accepted | 2026-11-27 | Open |
| P-7 | **Production-like load-testing infrastructure** | (D-14) | DevOps lead — to be named | Ready: requirements and thresholds (`load-test-environment.md`), harness (`ExamHarness.java`); the test runs within 2 working days of hand-over | Scenarios S1–S5 passed twice; reports and dashboards | Environment 2026-11-25 → test by 2026-11-27 | Open — requested 2026-11-15 |

## What is blocked by what

| Use | Needs |
|---|---|
| Real-candidate pilot (delivery baseline, by 2026-12-06) | P-1, P-2 (custody), P-3 (objective path), P-4 (candidate notices), P-5, P-6, P-7 |
| Production rollout | All of the above closed, plus the full penetration test and retest, a disaster-recovery drill and RB-3/RB-4 |

The table is updated in every daily report.
