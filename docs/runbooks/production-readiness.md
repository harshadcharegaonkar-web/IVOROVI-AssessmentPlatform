# Production readiness register

**Ivorovi is not production ready.** The items below must be resolved (or explicitly accepted by the product owner)
before real student data, documents or import files are processed in a production environment. No production-readiness
or legal-compliance claim is made until then. Updated at the 1.6 acceptance (2026-11-03) and for increment 1.7.

## Release blockers

| # | Blocker | Applies to | Status | Evidence required to close |
|---|---|---|---|---|
| RB-1 | **Real malware scanner (ClamAV or equivalent) deployed and verified in staging** (KI-23) | Student documents (1.5) **and** bulk-import files (1.6) | Open | clamd (with signature updates) running in staging; `IVOROVI_SCANNING_CLAMD_HOST` set; release test passed: EICAR document and EICAR import file rejected (`INFECTED`), scanner timeout → refused upload (`ERROR` / 503), no document downloadable before `CLEAN`, import original not downloadable without `CLEAN` |
| RB-2 | **`IVOROVI_DATA_KEY` operational management** (KI-51) | All encrypted personal fields and import row values | Open | Key in the production secret store; escrow backup; quarterly recovery drill executed once in staging and recorded (`runbooks/data-encryption-key.md`) |
| RB-3 | **Encryption key rotation tooling** (KI-49) | Same | Open | Re-encryption job (or offline tool) implemented and tested; rotation rehearsed in staging |
| RB-4 | **Student account lifecycle alignment** (KI-46) | Student sign-in accounts | Open (behaviour defined, not implemented) | Suspension/withdrawal/graduation/reinstatement rules implemented with tests (`architecture/07-student-lifecycle.md`) |
| RB-5 | **DPDP legal review and data-principal request workflow** (KI-48) | All personal data | Open — review brief prepared 2026-11-04 (`legal/dpdp-review-brief.md`); counsel to be engaged by the product owner | Legal review of notices, consent, purposes and retention; request workflow (access, correction, erasure with statutory retention) |
| RB-6 | Penetration test / DAST, production realm template without the dev password client (SA-03), unprivileged web image (SA-04) | Platform | Open (Phase 8) | Reports and checklist |

## Real-candidate testing (decision-register C-7)

No admission assessment with real candidates until RB-1, RB-2/RB-3, RB-4, RB-5 and RB-6 are resolved **and formally
verified**. Phase 2 milestone M1 uses synthetic candidates only.

## Before enabling integrations in production (increment 1.7)

Not release blockers for the platform, but required before an organization connects external systems:

* Set `IVOROVI_INTEGRATIONS_ALLOWED_HOSTS` to the institution's approved hosts (outbound allow-list) and verify in
  staging that private and metadata addresses are refused.
* Confirm that `allow-private-networks` / `allow-insecure-http` are unset (the application refuses to start with them
  outside DEVELOPMENT/TEST).
* Agree with each receiving system on webhook signature verification and de-duplication (`api/examples-1.7.md`).
* Review the scopes, IP allow-lists and expiry of every API client; rotate keys on a schedule.

## What scans files today (clarification requested at the 1.6 acceptance)

| Environment | Scanner | Evidence value |
|---|---|---|
| Production code path | `ClamAvScanner`: clamd `INSTREAM` over TCP. It is active **only** when `IVOROVI_SCANNING_CLAMD_HOST` is set, and used for student documents and import files | **Not yet run against a real clamd anywhere.** Its protocol handling is unit-tested against a simulated clamd socket (`ClamAvScannerTest`: clean, EICAR, timeout, unreachable, unexpected reply) |
| Automated tests (JUnit, CI) | `FakeMalwareScanner`: a **simulated** scanner (CLEAN unless the content contains the EICAR marker; can simulate unavailability) | Proves the quarantine, fail-closed and audit logic. **Not evidence of malware protection** |
| Local development, demonstration, benchmark, Playwright, smoke test | **No scanner configured.** Documents stay quarantined (`PENDING`). Import files are accepted only because the environment is DEVELOPMENT and are marked `NOT_SCANNED`; their originals cannot be downloaded | None |
| STAGING / PRODUCTION | Uploads of documents and import files are **refused (503)** until a scanner is configured (fail closed) | Policy is tested (`DocumentService.uploadsAllowed`, import upload path) |

File-type, extension and MIME checks are not malware protection, and the platform does not treat them as such.

## Closed at the 1.6 acceptance

* **KI-53** (refused document uploads not kept in the audit trail): fixed. Authenticated refusals are written to the
  hash-chained audit trail in their own transaction, with actor, tenant, institution, timestamp, reason, outcome and
  correlation ID. Unauthenticated refusals go to the platform `security_event` log (`UploadRefusalAuditIT`).
