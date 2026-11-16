# Seven-day execution — Day 7 final milestone report (2026-11-16)

The milestone is the objective-examination platform on synthetic data. Scope was set by the Day 6 review:
- KI-88 fix approved;
- a controlled rotation design for answer keys and paper seeds (design only);
- KI-79 as the highest priority;
- KI-89's concurrency gap;
- independent review of both parallel branches;
- the milestone priorities and this report.

Synthetic data only. Real PostgreSQL, Keycloak, Valkey, RabbitMQ and object storage. **No production readiness is
claimed, and none exists** (§10).

Three levels are kept apart throughout:
- **Functional:** built and working end to end.
- **Tested:** covered by automated tests that pass, locally and in CI.
- **Production-authorized:** cleared for real candidates. Today this is *nothing*.

## 1. Final integrated functionality inventory (objective-examination milestone)

| Capability | Functional | Tested (automated) | Production-authorized |
|---|---|---|---|
| Assessment authoring: approved-question selection, rules, marks and negative marks, preview, review, three-person publication, immutable versions | Yes | Backend IT, staff E2E journey 1 | No |
| Scheduling and enrolment: events, windows, bulk upload, invitations, accommodation extra time, session codes | Yes | Backend IT, E2E | No |
| Candidate authentication: candidate realm, e-mail verification, optional or required TOTP MFA, recovery; **immediate end of other sessions on password reset (KI-79, Day 7)** | Yes | Backend IT, real-Keycloak E2E | No |
| Check-in protection: layered limits (candidate, device, code, address) plus a durable account cooldown that works without Valkey; **concurrent guesses bounded (QA-D6-5, Day 7)** | Yes | Backend IT and adversarial IT | No |
| Delivery: sealed paper, server deadline, durable answer journal, autosave, resume after reload or crash, offline queue, idempotent submission, auto-submit at time-up | Yes | Backend IT, E2E, crash-recovery E2E (Session 3: 16/16 with the delivery process killed) | No |
| Timer: server time, extra time reaching the candidate within one heartbeat | Yes | Backend IT, E2E | No |
| Automatic scoring: deterministic, from the sealed paper and the journal | Yes | Backend IT | No |
| Independent result approval and publication (R-1): reviewer ≠ approver, approvers only exam controllers, everything audited, database-enforced | Yes | Backend IT, E2E | No |
| Invigilation: live monitor (attendance, state, connection interruptions), permanent incidents | Yes | Backend IT, E2E | No |
| Extra time during an examination (R-2): designated holders only, permanent record of previous and new expiry, optional second person per examination | Yes | Backend IT and adversarial IT, E2E | No |
| Termination: requested by an invigilator, approved by a different holder, database-bound to the approval (KI-93) | Yes | Backend IT and adversarial IT, E2E | No |
| Security regression and audit: tenant isolation (RLS), permission separation, append-only records, audit without secrets | Yes | `securityregression` suites, audit assertions | No |
| Field-encryption key rotation for 16 of 19 encrypted columns (Session 2, RB-3) | Yes (off by default) | Unit tests and `KeyRotationIT` | No |

The platform foundation (organizations, people, roles, academics, students, imports, dashboards, integrations,
question bank) was delivered in earlier increments and is in regression. It is not repeated here.

## 2. Commits and CI evidence

| Commit | What |
|---|---|
| `4bcbb9d` | KI-79 immediate revocation (back-channel logout, theme); QA-D6-3, -4, -5 fixed; KI-88 dedicated layout accounts; `ProductionRateLimitIT`; Session 3's adversarial tests (content, credited) |
| `232f5da`, `abefb41`, `8cf4087` | Session 2's key rotation (its own commits, environment identity, Session 2's session trailer) |
| `6cc37a3` | Merge of Session 2's branch after independent review |
| `0dd101b` | This report; register; protected-value rotation design; wording fixes |

| Run | Commit | Result |
|---|---|---|
| [43803503040](https://github.com/harshadcharegaonkar-web/IVOROVI-AssessmentPlatform/actions/runs/43803503040) | `0dd101b` (contains `4bcbb9d` and the Session 2 merge) | **All jobs green on the first attempt, with flaky tests now failing the run (none).** Backend, frontend, security scan green. Staff E2E 119 passed (including the 48 tablet and mobile layout tests on the new accounts: KI-88's first CI pass). Exam E2E 24 passed, 1 skipped. The KI-79 timeline in CI: right after the reset, refresh 400 and 400, API 401 and 401 on the very first check; 9 back-channel logouts processed, none refused |

This report's own commit starts one more CI run.

## 3. Backend, frontend and browser E2E results

| Suite | Result |
|---|---|
| Backend, full suite, merged code (local) | **530 passed, 0 failed, 0 skipped** (106 classes); Day 6 ended at 500 |
| New today | `BackchannelLogoutIT` 3, `ProductionRateLimitIT` 2, `PasswordResetAdversarialIT` 7, `CheckInGuardAdversarialIT` 4, `ExamOperationsAdversarialIT` 15 (Session 3), `FieldCipherKeyRingTest` 4, `KeyRotationIT` 5 (Session 2) |
| Frontend unit | 92 passed; typecheck and lint clean |
| Staff browser E2E (local, production nginx build, `--retries=0`) | **115 passed**; 4 not run here: the maths-rendering projects for Firefox, WebKit and Edge need browsers this environment does not have (CI installs them). The tablet and mobile projects ran on the new layout accounts: 48/48 |
| Exam browser E2E (local, real Keycloak, `--retries=0`) | **23 passed**, 1 skipped (optional crash test). 1 local-only failure: the security-headers test read the Vite dev server, which by design sends none of the production headers (`vite preview` and the nginx container do); CI runs it against the production container. The KI-79 tests passed on their first checks, with no waiting |

**CI changes.**
- Playwright's `failOnFlakyTests` is on: a test that fails and then passes on the retry now **fails the run**. Day 6's hidden KI-79 failure cannot recur unseen.
- The revocation timeline is printed in every run.

## 4. Open security defects and their severity

| Issue | Severity | Status |
|---|---|---|
| KI-79: password reset and other sessions | Medium | **Fixed Day 7** (immediate, synchronous revocation; measured cause). Open until repeated first-attempt CI passes |
| KI-49: key rotation for answer keys, explanations, paper seeds and blind indexes | Medium | Open. 16 of 19 columns rotatable; design for the rest under your review (§7) |
| KI-51: secret store, escrow and recovery drill | Release blocker | Open (P-2, external) |
| KI-71, KI-72, KI-73: incident detection gaps | Medium | Open (delivery hardening, M3c completion) |
| KI-77, KI-78: screen readers, real-device Safari | Medium | Open (P-6, P-5, external) |
| QA-D6-6: the examination window is enforced in the service, not in the database; an approved second-person grant re-checks the window captured at request time | Info | Open; a database check of the new expiry against the window is proposed for M3c |
| QA-D6-7: `iat` is compared in whole seconds, so a token obtained in the reset's own second before the reset is accepted | Info | Accepted by design; back-channel logout (Day 7) now ends that session regardless |
| KI-82: scheduling reads other modules' tables | Low | Deferred (documented) |
| KI-88: E2E rate-limit exhaustion (test design, not a product defect) | Low | Fix applied Day 7; open until repeated CI passes |
| KI-93, KI-94, KI-95, QA-D6-5 | Medium, Low, Low, Low | **Fixed** (KI-93 CI-verified on Day 6; the rest with today's CI) |

Release blockers RB-1 to RB-6 are all open (§10).

## 5. Full examination workflow demonstration

The workflow runs end to end in one automated journey, `frontend/apps/exam/e2e/exam-journey.spec.ts`, on the real stack:

1. A designer authors an assessment from approved questions. A reviewer reviews it, and a publisher with the privileged right publishes an immutable version.
2. The controller schedules it, uploads candidates and sends invitations (e-mail via Mailpit).
3. The candidate registers in the candidate realm, verifies the e-mail address, signs in and checks in with a single-use session code.
4. The candidate answers. Saves are journaled durably; a reload resumes; the timer is server-based.
5. The invigilator monitor shows the candidate online and writing. A designated invigilator or controller extends the time (+5 minutes). The candidate sees the new end time, and the grant history shows previous → new expiry.
6. The head of institution asks to end an examination. A different controller approves; the candidate sees the end, and the answers are kept.
7. Submission or auto-submit, then automatic scoring.
8. A reviewer requests publication; an approver who is not the reviewer approves; the candidate sees the result.
9. The audit trail records each step, without answers, keys or secrets.

Screenshots from the real runs:
- Day 5 report: monitor, extend dialog, candidate notice, end request, ended screen.
- Day 6 report: R-2 policy, revised end time, grant history.

## 6. Remaining contractual functionality (scope unchanged)

From `delivery-baseline.md` §1–§3. Nothing has been removed.

| Module | Status |
|---|---|
| M1b authoring completion (sections, templates, blueprint constraints) | Missing |
| M2b scheduling completion (venues, rooms, seats, admit-card PDFs, accommodation types) | Missing |
| M3c delivery hardening (KI-71 to KI-73, operator dashboard, QA-D6-6) | Partial |
| M4b results completion (ranks, normalization, scorecards, queries and grievances, result analytics) | Missing |
| M5 question bank completion (blank, matching, ordering, secure banks, QTI) | Missing |
| M6 admissions; M7 manual evaluation; M8 academic assessments; M13 placement | Missing |
| M9 proctoring L1; M10 proctoring L2/L3 (needs DPDP consent and model hosting) | Missing |
| M11 coding assessments (needs KVM nodes); M12 AI gateway (needs the provider) | Missing |
| M14 institutional integrations (SSO, LTI 1.3, OneRoster) | Missing |
| M15 platform administration (provisioning, branding, bulk users, licensing) | Missing |
| M16 compliance (RB-3 rotation partly done today, RB-4 account lifecycle, DPDP workflow) | Partial |
| M17 deployment (Helm, production Keycloak, on-premises bundle, monitoring, disaster recovery) | Partial (Compose and CI only) |

## 7. Key-rotation status and KI-49 remediation plan

**Done (Session 2, reviewed and merged):**
- a key ring with the `v2:<keyId>:` format (v1 readable for ever);
- a leased, resumable, compare-and-set re-encryption job for 16 columns;
- a fail-closed startup check;
- a pinned blind-index key;
- the runbook.

It is off by default and rollback-safe.

**Not done:**
- answer keys and explanations;
- paper seeds (counted, never rewritten);
- blind-index key rotation;
- the real secret store and recovery drill (P-2).

**Plan for your review:** `docs/architecture/protected-value-key-rotation.md`. In short:
- a dedicated rotator database role and operator command, with no application path;
- the guards accept a rewrite only from that role, only of the ciphertext column, only against a matching append-only ledger row of an approved four-eyes run;
- plaintext identity is verified before and after, and against independent references (`key_hash`, `paper_hash`, or a keyed digest);
- a new row-bound format v3 prevents ciphertext swapping;
- interruption recovery and rollback through the same path;
- key retirement only when nothing references the old key.

Blind-index strategy per index is in the same document. Effort: about 5 engineering days after approval.
**KI-49 stays open.**

## 8. External dependency owners and delivery dates

Detail: `docs/runbooks/production-dependencies.md`. **No owner has been named yet.** The dates are proposals that
assume a start on 2026-11-17.

| # | Dependency | Suggested owner | Proposed completion | Status |
|---|---|---|---|---|
| S-0 | Staging on the production target | DevOps lead | 11-21 | Not started |
| P-1 | ClamAV | DevOps lead | 11-24 | Not started |
| P-2 | Key custody, backup, recovery, rotation | DevOps lead + security officer | 11-27 custody, 12-04 rotation | Engineering share partly done (§7) |
| P-3 | Penetration test | Security vendor | Test 11-30 → 12-03; retest 12-05 | Vendor not engaged |
| P-4 | DPDP review | Legal counsel | Notices 12-02 | Counsel not engaged |
| P-5 | Real-device Safari | QA with Apple devices | 11-27 | Not started |
| P-6 | Screen readers | Accessibility tester | 11-27 | Not started |
| P-7 | Load-test infrastructure | DevOps lead | Environment 11-25, test 11-27 | Not started |

## 9. Updated full-platform engineering estimate

- **Baseline:** about 72 one-agent engineering days. Scope is unchanged.
- **Remaining after Day 7:** about 50 days, plus 5 for the protected-value rotation, plus about 2 for M3c follow-ups found this week. **About 57 days.**

| Scenario | Full functional scope | Notes |
|---|---|---|
| One primary session | About **mid-February 2027** | 57 working days from 2026-11-18 |
| Three sessions | About **2026-12-30 to 2027-01-15** | Capacity was partly demonstrated this week: Session 2 delivered RB-3 (merged); Session 3 found 7 issues and delivered 26 tests (merged). Both needed review and rework time, about 20 %. 2026-12-20 is no longer realistic |
| External gates | Pilot 2026-12-06 **only** if every owner starts on 2026-11-17; rollout mid-January 2027 at the earliest | None started |

## 10. Production-readiness assessment

**Not production-ready. Not authorized for real candidates.**

| Gate | Blocking |
|---|---|
| RB-1 ClamAV (P-1) | Yes |
| RB-2 key custody (P-2), RB-3 rotation of all values (KI-49) | Yes |
| RB-4 student account lifecycle | Yes |
| RB-5 DPDP (P-4) | Yes |
| RB-6 penetration test (P-3) | Yes |
| Load at target concurrency (P-7, D-14) | Yes, for the pilot size |
| Real-device Safari (P-5) and screen readers (P-6) | Yes, for those candidates |
| Production Keycloak realm without the development client (SA-03); this repository's realm files are development only | Yes |
| KI-79 and KI-88 repeated first-attempt CI evidence | Yes, until shown |

## Release-candidate instructions (synthetic data only)

Build and run on one machine with Docker:

```bash
./scripts/dev-setup.sh
```

```bash
cd deploy/compose && docker compose --profile app up -d --build --wait --wait-timeout 300
```

| Service | Address |
|---|---|
| Staff app | http://localhost:8081 |
| Exam app | http://localhost:8082 |
| Keycloak | http://localhost:8180 |
| Mailpit (invitations, verification) | http://localhost:8025 |

- Staff accounts: `alice.admin@aurora.test` (administrator) and `eshan.controller@aurora.test` (exam controller), with
  the password `DEV_USER_PASSWORD` from `deploy/compose/.env`.
- Demonstration data: `scripts/demo-data.sh rebuild`.
- The whole journey as automated proof:

```bash
cd frontend && set -a && . ../deploy/compose/.env && set +a && E2E_BASE_URL=http://localhost:8082 E2E_STAFF_URL=http://localhost:8081 pnpm --filter @ivorovi/exam e2e
```

The release candidate is the commit in the CI row above. It is a **milestone build on synthetic data**, not a release
for production.
