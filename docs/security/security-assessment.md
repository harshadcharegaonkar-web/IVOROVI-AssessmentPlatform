# Security assessment: Phase 0 foundation

* Date: 2026-11-02 · Scope: code in this repository at commit `a239681` and later documentation commits
* Method: design review, automated tests (listed per control), a full-history secret scan, and a dependency
  audit. **No external penetration test or DAST has been performed yet.**
* Legend: ✅ verified by an automated test or a reproducible check · 🟨 implemented, partially verified · ⬜ planned (not implemented)

## 1. Assets and threats in scope

| Asset | Main threats |
|---|---|
| Tenant data (institutions, users, audit trail) | Cross-tenant disclosure or modification (broken isolation, IDOR) |
| Authorization state | Privilege escalation, forged tokens or claims |
| Audit trail | Tampering or deletion by insiders |
| Secrets (DB, broker, storage, IdP) | Leakage through VCS, logs or images |
| Availability | Request floods, expensive queries (rate limiting is not yet implemented) |

## 2. Controls

| # | Control | Status | Evidence |
|---|---|---|---|
| C1 | Tenant bound only to the signed `tid` claim. Headers and parameters are ignored | ✅ | `TenantIsolationIT.tenantHeadersAndParametersAreIgnored`, `userOfTenantBCannotClaimTenantA` |
| C2 | Token validation: signature, issuer, audience, expiry | ✅ | `TenantIsolationIT.Establishment` (untrusted key, wrong issuer, wrong audience, expired) |
| C3 | Tenant must be ACTIVE/TRIAL and the account ACTIVE in that tenant | ✅ | suspended tenant, suspended account and no-account tests, plus smoke test with real Keycloak |
| C4 | Database row-level security on every tenant table. App role without `BYPASSRLS` | ✅ | `DatabaseLevel` tests incl. "every `tenant_id` table has RLS" (a build gate) |
| C5 | Cross-tenant resource access yields 404 (no existence oracle) | ✅ | `otherTenantsInstitutionIsNotFound`, E2E "other tenant's data is not reachable" |
| C6 | Server-side, scope-aware authorization. Token role claims ignored | ✅ | `AuthorizationIT` (9 tests incl. escalation attempts and forged role claims) |
| C7 | Denied attempts are audited | ✅ | `facultyCannotReadAuditTrailAndAttemptIsAudited` |
| C8 | Audit trail is append-only (DB grants) and hash-chained | ✅ | `applicationRoleCannotModifyOrDeleteAuditEvents`, `hashChainVerifiesAndDetectsTampering` |
| C9 | Errors never leak internals (stack traces, parser messages) | ✅ | `ErrorHandlingIT` |
| C10 | Input validation (bean validation, pattern-restricted sort, page-size cap) | ✅ | `ErrorHandlingIT` |
| C11 | Object storage namespaced per tenant. Path traversal rejected | ✅ | `InfrastructureAdaptersIT` |
| C12 | AI features off by default. A deployment ceiling can force them off | ✅ | `FeatureFlagIT`, `FeatureResolverTest` |
| C13 | Browser hardening: CSP, nosniff, referrer policy, COOP. Tokens in memory only. Open-redirect-safe return paths | 🟨 | Headers verified with curl, `auth.test.ts`. No automated header test in CI yet |
| C14 | Secrets never committed. Generated per environment | ✅ | Full-history scan (318 blobs, 0 true findings), exact-value check of generated `.env`, `.gitignore` covers `.env*` and key material |
| C15 | Least-privilege CI (`contents: read`), SHA-pinned actions | ✅ | `.github/workflows/ci.yml` review |
| C16 | Dependency and secret scanning in CI (Trivy fs including the Java lockfile, plus both images) | ✅ | Executed in CI. The first run found SA-08 and SA-09; run 41444289316 is clean at HIGH/CRITICAL (fixable) |
| C17 | Containers run as non-root (API) | 🟨 | API Dockerfile `USER ivorovi`. The nginx master process runs as root (worker processes do not) |
| C18 | Rate limiting | ✅ | Increment 1.0: per user/tenant buckets, failed-auth limit per IP; `RateLimitIT` |
| C19 | Idempotency keys for create/submit | ✅ | Increment 1.0: tenant/user-scoped, RLS; `IdempotencyIT` |
| C23 | Invitations: 256-bit single-use expiring tokens, SHA-256 at rest, link in URL fragment, never logged/audited, public endpoints rate limited per IP, unknown/used/expired/revoked tokens indistinguishable | ✅ | `UserManagementIT.Invitations` (incl. `tokensAreStoredOnlyAsHashesAndNeverAudited`), smoke test, E2E |
| C24 | Least-privilege identity provisioning (`ivorovi-provisioner`: manage/view/query-users only) | ✅ | Probe: clients, roles and master realm return 403; secret generated per environment (`KEYCLOAK_PROVISIONER_SECRET`) |
| C25 | Anti-escalation for role assignment, no self-assignment, last-organization-admin protection, scoped people management (all units) | ✅ | `UserManagementIT.Assignments`, `Lifecycle`, E2E scoped administrator |
| C26 | Suspension/deactivation take effect on the next request (principal cache eviction) and in Keycloak (disabled, sessions ended) | ✅ | `Lifecycle.suspendReactivateDeactivateActivate`, smoke test, E2E (Keycloak refuses sign-in) |
| C27 | Custom roles bounded by the editor's ceiling at the role scope; only catalogued, available permissions; reserved codes refused; platform permissions excluded (service + DB check) | ✅ | `RoleManagementIT.Catalogue`, `CeilingAndScope`, smoke |
| C28 | System roles immutable through the API; denied attempts audited | ✅ | `systemRolesAreProtected`, smoke |
| C29 | Role deactivation/edit/archive take effect on the next request (tenant-wide principal cache invalidation); concurrent archive/assign/edit serialised | ✅ | `deactivationSuspendsAssignments…`, `editingARole…`, `Concurrency` |
| C30 | Student personal and guardian contact fields encrypted at the application layer (AES-256-GCM; column and tenant bound as associated data); API refuses to start without the key | ✅ | `personalDetailsAreEncryptedRestrictedAndNeverLogged` |
| C31 | Personal student data behind a separate permission; faculty see profiles without it; guardian views audited; audit and event logs free of personal values | ✅ | same test, smoke, Playwright |
| C32 | Student documents quarantined until a scanner reports CLEAN; PENDING/INFECTED/ERROR never served; uploads refused without a scanner in STAGING/PRODUCTION (fail closed); content type from magic bytes; downloads audited and streamed with `nosniff`/`no-store` | ✅ (ClamAV deployment pending: KI-23 release blocker) | `Documents` group, `ClamAvScannerTest`, smoke |
| C33 | Student sign-in accounts only on demand, by a separate permission, with the STUDENT role only; linked accounts can never receive another role | ✅ | `accountsAreCreatedOnDemandWithTheStudentRoleOnly` |
| C34 | Student lifecycle and enrolment changes validated server-side, serialised (row locks; batch lock for capacity) and recorded with reason, actor and effective date | ✅ | `Lifecycle`, `AdmissionAndEnrolment` |
| C20 | DAST (OWASP ZAP), external penetration test | ⬜ | Phase 8, plus before first production tenant |
| C21 | Field-level encryption of sensitive PII | ⬜ | When such fields are introduced (Phase 1 does not store government IDs) |
| C22 | Production IdP hardening (TLS, MFA policies, brute-force settings beyond dev realm) | ⬜ | Phase 8 deployment work |

## 3. Findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| SA-01 | Medium | No rate limiting: authenticated users can issue unbounded requests; each performs 3 DB lookups | Fixed in increment 1.0 |
| SA-02 | Low | Outbox table is readable by the app role in platform context. Mitigated by RLS policy and kernel-only access, but payloads must never contain secrets | Accepted, documented |
| SA-03 | Low | Dev realm contains a password-grant client (`ivorovi-dev-cli`) | Accepted for dev only. A production realm template must not include it (Phase 8 checklist) |
| SA-04 | Low | nginx master process runs as root in the web image | Open → Phase 8 (unprivileged nginx image) |
| SA-05 | Info | Seeder code and Datafaker ship in the production jar (guarded by environment check) | Fixed in increment 1.0 (`backend:devtools`) |
| SA-06 | Info | Moderate DoS advisory in `minimatch` (dev tooling only) | Fixed via override in `a239681` |
| SA-07 | Info | CI security scan not yet executed | Closed (run 41444289316) |
| SA-08 | High | `amqp-client` 5.30.0 with 4 HIGH CVEs (Boot-managed) | Fixed: 5.37.0 override |
| SA-09 | Critical | Web image base with CRITICAL brotli CVE | Fixed: nginx 1.30.6 / Alpine 3.24 + `apk upgrade` |

## 4. Residual risk statement

The tenant-isolation and authorization model is verified by automated tests at both the API and the database
layer. The main open risks before any production use are SA-01 (abuse and availability), the unexecuted CI
security scans (SA-07), and the absence of DAST and penetration testing (C20). For student data, the ClamAV deployment
and release test (KI-23) and the legal review of DPDP obligations (KI-48) must be completed before real student records
or documents are accepted.
