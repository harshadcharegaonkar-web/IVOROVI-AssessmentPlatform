# Production dependency register P-1 to P-7 (Day 6, 2026-11-15)

These are the external gates that must pass before any production use with real candidates. **The platform is not
production-ready** until every item is closed with evidence. Each item maps to the release-blocker register in
`production-readiness.md` (RB-n).

**Status of owners and dates.**
- Every owner below is a **suggested role or vendor type, not a confirmed person**. The product owner names them (Day 5 review, point 4).
- Every date is a **proposal**, computed from a start on Tuesday 2026-11-17, with working days Monday to Friday.
- A date moves day for day with a later start.
- The conditional real-candidate pilot (2026-12-06) needs **all seven** items closed. It remains conditional on them.

**Shared prerequisite (S-0): a staging environment on the production target.** P-1, P-2, P-3, P-5 and P-6 all test
against it. It needs:
- TLS domains for web, exam, API and identity;
- managed PostgreSQL with point-in-time recovery, Valkey, RabbitMQ and object storage;
- production Keycloak realms without the development password client (SA-03);
- deployment from CI.

Owner: DevOps lead (to be named). Effort: about 3 DevOps days. Proposed: 2026-11-17 → 2026-11-21. If S-0 slips, P-1,
P-3, P-5 and P-6 slip with it.

## Summary

| # | Dependency | Suggested owner (not confirmed) | Effort (external / engineering) | Lead time | Proposed start → completion | Blocks |
|---|---|---|---|---|---|---|
| P-1 | ClamAV deployment and malware-scan validation | DevOps lead | 1–2 days / 0.5 day | 3–5 working days | 11-17 → 11-24 | Pilot (RB-1, release blocker) |
| P-2 | Encryption-key custody, backup and recovery | DevOps lead + security officer (dual control) | 2.5 days / 1.5 days (rotation tool in progress) | 5 working days | 11-17 → 11-27 custody; 12-04 rotation | Pilot (RB-2), production (RB-3) |
| P-3 | External penetration test and remediation | External security vendor (CERT-In empanelled recommended) | 5–8 test days + 2 retest / 0.5 day + 2–4 days fixes | 1–2 weeks to contract | Contract by 11-20; test 11-30 → 12-03; retest 12-05 | Pilot (objective path); production (full scope) |
| P-4 | DPDP legal review | Legal counsel with DPDP practice | 5–8 days / 1 day notices (pilot), 7 days workflow (M16) | 2–3 weeks | Brief 11-17; notices approved 12-02; built 12-04 | Pilot (candidate notices), production (full review) |
| P-5 | Real-device Safari validation | QA tester with Apple devices (internal, or a real-device lab vendor) | 1.5 days / 1–2 days fixes | 2–3 days | 11-24 → 11-27 | Pilot on Apple devices |
| P-6 | Screen-reader accessibility testing | Accessibility tester (internal or vendor) | 2 days / 0.25 day script + 1–2 days fixes | 3–5 days | 11-24 → 11-27 | Pilot for candidates using assistive technology |
| P-7 | Production-like load-test infrastructure | DevOps lead | 2–3 days / 1.5 days to run | 5 working days (cloud quota) | 11-17 → environment 11-25 → test done 11-27 | Pilot size (2,000-candidate gate) |

## P-1 ClamAV deployment and real malware-scan validation (RB-1)

- **Exact requirement.**
  - clamd (ClamAV 1.x) with freshclam signature updates, reachable only from the API process over the private network (TCP 3310), in staging and production.
  - The API is configured with `IVOROVI_SCANNING_CLAMD_HOST` and `IVOROVI_SCANNING_CLAMD_PORT`.
  - Signatures are never older than 24 hours, and their age is monitored.
- **Required deliverable.**
  - clamd running in staging and production, with update monitoring and an alert on stale signatures.
  - A release-test record:
    - an EICAR document, an import file and a question image are each refused (`INFECTED`);
    - a scanner timeout or outage refuses the upload (`ERROR`, 503, fail closed);
    - nothing is served before `CLEAN`.
- **Suggested owner.** DevOps lead (to be named).
- **Effort and lead time.** DevOps 1–2 days. Engineering 0.5 day to run the release test with DevOps. Lead time 3–5 working days.
- **Infrastructure and access.**
  - 2 vCPU / 4 GB per clamd instance (signatures are held in memory).
  - Outbound access to the ClamAV mirrors or a private mirror.
  - A network policy that allows only API to clamd.
  - Engineering needs read access to the scanner logs and the alert.
- **Engineering share (ready).** The `ClamAvScanner` adapter, fail-closed refusal without a scanner, quarantine and re-encoding.
- **Proposed dates.** Start 2026-11-17, complete 2026-11-24 (needs S-0).
- **Impact if delayed.** Uploads stay refused in staging and production by design (fail closed). No student documents, import files or question images can be used with real data. The pilot is blocked: RB-1 is a release blocker.

## P-2 Encryption-key custody, backup and recovery (RB-2, RB-3)

- **Exact requirement.**
  - `IVOROVI_DATA_KEY` (the field-encryption key for personal data) is generated in and served from a secret store backed by a KMS or HSM. It never appears in files, images, repositories or logs.
  - Only the API's service identity may read it.
  - An escrow copy is held under dual control.
  - A recovery drill is performed in staging.
  - Rotation is rehearsed with the rotation tool (RB-3).
- **Required deliverable.**
  - The secret-store configuration and access policy.
  - The escrow record (who holds which part, where).
  - A drill report (restore of a staging database and key, data readable afterwards).
  - A rotation-rehearsal report.
- **Suggested owner.** DevOps lead and security officer, under dual control (to be named).
- **Effort and lead time.**
  - DevOps 2 days, security officer 0.5 day.
  - Engineering: the rotation tool (Session 2, about 1 day, in progress under D-17), plus a 0.5-day rehearsal.
  - Lead time 5 working days.
- **Infrastructure and access.** A secret store (for example Vault or a cloud KMS with secret manager), audit logging of key access, and a staging environment for the drill.
- **Proposed dates.** Start 2026-11-17. Custody and escrow by 2026-11-27. Rotation rehearsed by 2026-12-04.
- **Impact if delayed.** No real personal data may be written: losing the key means permanently losing every encrypted field (candidate e-mails, student identity data). The pilot is blocked. Without rotation (RB-3), production rollout is blocked.

## P-3 External penetration test and remediation (RB-6)

- **Exact requirement.** An independent test of the objective-examination path at OWASP ASVS level 2 depth:
  - the staff and exam web applications;
  - the staff and delivery APIs;
  - the Keycloak configuration;
  - multi-tenant isolation;
  - answer-key leakage;
  - replay and idempotency of answers and submissions;
  - timing manipulation;
  - upload handling;
  - the four-eyes approvals (results, termination, extra time, privileged roles).
- **Required deliverable.**
  - A report with severities and reproduction steps.
  - Critical and high findings fixed and retested, with a retest letter.
  - Residual risks accepted in writing by the product owner.
- **Suggested owner.** An external security vendor, CERT-In empanelled recommended (to be contracted by the product owner).
- **Effort and lead time.**
  - Vendor: 5–8 test days plus 2 retest days. Contracting usually takes 1–2 weeks.
  - Engineering: 0.5 day of preparation (production realm template without the development client, SA-03; unprivileged web image, SA-04; test accounts; scope document), plus a reserve of 2–4 days for fixes.
- **Infrastructure and access.** Staging with production configuration (needs S-0, P-1 and P-2 custody), synthetic organizations, and test accounts for each role.
- **Proposed dates.** Contract signed by 2026-11-20. Test 2026-11-30 → 2026-12-03. Fixes by 2026-12-04. Retest 2026-12-05.
- **Impact if delayed.** The pilot cannot start with real candidates. Each week of delay moves the pilot by a week. The full test (coding, AI, proctoring) before production rollout is a separate, later round.

## P-4 DPDP legal review (RB-5)

- **Exact requirement.** Counsel reviews, against the Digital Personal Data Protection Act 2023 and its Rules:
  - candidate and student notices;
  - purposes;
  - consent, including verifiable parental consent for minors;
  - the retention schedule and statutory academic retention;
  - data-principal rights;
  - processor agreements (cloud, e-mail; later AI and proctoring).

  The brief is in `legal/dpdp-review-brief.md`.
- **Required deliverable.**
  - A written opinion.
  - Approved candidate notice and consent texts.
  - An approved retention schedule.
  - A list of required changes.
- **Suggested owner.** Legal counsel with a DPDP practice (to be engaged by the product owner).
- **Effort and lead time.**
  - Counsel: 5–8 days. Lead time 2–3 weeks.
  - Engineering: 1 day to implement the approved notices for the pilot. The full data-principal request workflow (M16) is about 7 days after counsel's input.
- **Infrastructure and access.** None. Counsel needs the brief, screenshots and data-flow descriptions; engineering provides them.
- **Proposed dates.** Brief sent 2026-11-17. Notices approved by 2026-12-02. Notices built by 2026-12-04. Full review before production rollout.
- **Impact if delayed.** Personal data of real candidates cannot be processed on an approved legal basis, so the pilot is blocked. Proctoring (M10) and AI (M12) stay gated longer.

## P-5 Real-device Safari validation (KI-78)

- **Exact requirement.**
  - Physical iPhone (iOS current and current − 1), iPad and Mac Safari (current and current − 1).
  - Coverage:
    - the full candidate journey: register, check in, write with autosave, reload and resume, submit, time-up;
    - maths and image rendering;
    - the key staff screens (monitor, results).
  - Playwright WebKit in CI does not count as real-device Safari.
- **Required deliverable.** A test record for each device and browser (pass or fail, screenshots), and blocking defects filed.
- **Suggested owner.** A QA tester with Apple devices: internal, or a real-device lab vendor (a vendor needs approval).
- **Effort and lead time.** Tester 1.5 days. Engineering 1–2 days of fixes in reserve. Lead time 2–3 days.
- **Infrastructure and access.** A staging URL reachable from the devices (public TLS, S-0), test accounts, and the test checklist (ready).
- **Proposed dates.** 2026-11-24 → 2026-11-27.
- **Impact if delayed.** Apple-device candidates cannot be supported in the pilot. The pilot would have to exclude those devices in writing, or wait.

## P-6 Screen-reader accessibility testing (KI-77)

- **Exact requirement.**
  - Screen readers: NVDA with Firefox and Chrome, JAWS with Chrome, VoiceOver on macOS and iOS, TalkBack on Android.
  - Coverage: the candidate journey, maths, question navigation, image descriptions, keyboard-only use and form errors, against WCAG 2.1 AA.
- **Required deliverable.** A report for each reader, with findings by WCAG criterion. Findings are fixed or accepted in writing.
- **Suggested owner.** An accessibility tester, internal or vendor (to be named).
- **Effort and lead time.** Tester 2 days. Engineering 0.25 day for the manual test script, plus 1–2 days of fixes in reserve. Lead time 3–5 days.
- **Infrastructure and access.** The staging URL (S-0), test accounts, and Windows, macOS, iOS and Android devices.
- **Proposed dates.** 2026-11-24 → 2026-11-27.
- **Impact if delayed.** Candidates who rely on assistive technology cannot be supported, which risks the institution's accommodation obligations. Excluding them is not recommended.

## P-7 Production-like load-test infrastructure (D-14)

- **Exact requirement.** The environment, scenarios and thresholds fixed in `load-test-environment.md`:
  - 4 delivery and 2 administrative instances;
  - a PostgreSQL primary and replica;
  - Valkey with a replica;
  - 3 separate load generators;
  - monitoring.
- **Required deliverable.** The environment handed over with access. Engineering then runs S1–S5 twice each within 2 working days and delivers:
  - the reports;
  - the dashboards;
  - a pass/fail table;
  - the defects found;
  - corrected sizing.
- **Suggested owner.** DevOps lead (to be named).
- **Effort and lead time.** DevOps 2–3 days, with a lead time of about 5 working days for cloud quota. Engineering 1.5 days.
- **Infrastructure and access.** Listed in `load-test-environment.md`, including shell access to the generators and the ability to kill a delivery instance and trigger a database failover.
- **Proposed dates.** Start 2026-11-17. Environment 2026-11-25. Test complete 2026-11-27.
- **Impact if delayed.** There is no evidence that the platform meets the 2,000-candidate event gate. The Day 4 single-machine run is not production-scale validation. The pilot size would be limited to what has been validated.

## Critical path to the pilot

S-0 staging (11-21) → P-1 ClamAV (11-24) and P-2 custody (11-27) → P-3 penetration test (11-30 → 12-03, fixes 12-04,
retest 12-05) → pilot readiness review 12-05 → pilot 12-06.

P-4, P-5, P-6 and P-7 run in parallel and must also be closed by 12-05. The schedule has **no slack**. Any external item
that starts after 2026-11-17, or any critical or high penetration-test finding that takes longer than the reserve, moves
the pilot.

This register is updated in every daily report.
