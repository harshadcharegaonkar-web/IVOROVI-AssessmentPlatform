# Known issues register

Status values: **Open**, **Planned** (scheduled in a phase), **Accepted** (conscious risk, documented), **Fixed**.
Only items verified as fixed by a test or a reproducible check are marked Fixed.

| ID | Area | Issue | Severity | Status | Target / note |
|---|---|---|---|---|---|
| KI-01 | CI | Pipeline not yet executed on GitHub (acceptance criterion 9 partial) | High (process) | Fixed | Run 41444289316 green on `1dba95c` |
| KI-02 | Security | No API rate limiting (SA-01) | Medium | Fixed | Increment 1.0: Valkey token buckets, `RateLimitIT` |
| KI-03 | Performance | 3 DB lookups per authenticated request (tenant, account, grants) | Medium | Fixed | Increment 1.0: principal + tenant caches, `PrincipalCacheIT` |
| KI-04 | API | No `Idempotency-Key` support | Medium | Fixed | Increment 1.0: `@Idempotent`, `IdempotencyIT` |
| KI-05 | Frontend | Single 635 kB JS bundle (195 kB gzip), no code splitting | Low | Fixed (with deviation KI-24) | Lazy routes; initial 176 kB gzip incl. shell; pages lazy |
| KI-06 | Audit | Chain verification is synchronous O(n) | Low | Planned | Background job before large tenants (Phase 2) |
| KI-07 | Build | 105 MB API jar includes seeder/Datafaker and two AWS HTTP clients | Low | Fixed | 90.3 MB; seeder in `backend:devtools`; ArchUnit/Modulith runtime and AWS Netty client removed |
| KI-08 | Events | No consumer inbox/de-duplication helper. No retention cleanup of published outbox rows | Low | Partly fixed | Retention job in 1.0; inbox helper with first consumer (Phase 2) |
| KI-09 | Observability | No OpenTelemetry trace export (correlation id only) | Low | Planned | Phase 2 |
| KI-10 | Dev tooling | Seeder with the same `random-seed` fails on duplicate tenant codes | Info | Accepted | Documented in runbook |
| KI-11 | Dev tooling | Keycloak realm import only on first start | Info | Accepted | Documented. Reset procedure in runbook |
| KI-12 | Security | Dev realm has a password-grant CLI client | Low | Accepted | Must not exist in production realms (Phase 8 checklist) |
| KI-13 | Security | nginx master runs as root in web image | Low | Planned | Phase 8 |
| KI-14 | Deployment | No Helm chart, production Keycloak config, or digest-pinned images | Medium | Planned | Phase 8 |
| KI-15 | Code execution | Kata PoC needs KVM; build sandbox has no `/dev/kvm` | Info | Open | PoC must run on a KVM-capable host (ADR-0004) |
| KI-16 | Supply chain | Docker Hub rate limits observed while pulling images | Low | Open | Mirror base images to a controlled registry before production |
| KI-17 | Dependencies | `minimatch` moderate DoS advisory (dev tooling) | Info | Fixed | pnpm override, `pnpm audit` clean (`a239681`) |
| KI-18 | CI | trivy-action referenced with a non-existent tag (`0.33.1` instead of `v0.33.1`) | High (would fail CI) | Fixed | Pinned to SHA of `v0.36.0` (`a239681`) |
| KI-20 | Dependencies | `com.rabbitmq:amqp-client` 5.30.0 (managed by Spring Boot 4.1.2): 4 HIGH CVEs | High | Fixed | Overridden to 5.37.0 in `1dba95c`; **remove the override when the Boot BOM ships ≥ 5.34.0** |
| KI-21 | Container image | `nginx:1.29-alpine` base (Alpine 3.23.4): CRITICAL brotli CVE-2026-111497 | Critical | Fixed | `nginx:1.30.6-alpine3.24` + `apk upgrade` in `1dba95c` |
| KI-22 | CI | Backend test-report artifact cannot be downloaded from the build sandbox (artifact host blocked by its network policy) | Info | Accepted | Reports remain available in the GitHub UI |
| KI-23 | Documents | No malware scanner integrated (ADR-0006) | **Release blocker** for real student document uploads | Planned | ClamAV or approved equivalent before first production document upload |
| KI-24 | Frontend | Initial JS budget 120 kB (increment 1.0 AC8) is below the approved stack's floor; actual 176 kB | Low | **Decision needed** | Proposed CI budget 180 kB (see increment-1.0.md §Deviations) |
| KI-25 | Dev environment | Build-sandbox containers cannot run `apk upgrade` (no package mirror access); images verified in CI only | Info | Accepted | Environment limitation, not a product issue |
| KI-26 | Visual QA | Pixel-diff baselines not yet automated | Low | Planned | Increment 1.1, baselines generated in CI's browser image |
| KI-27 | Identity | Roles granting no permissions were omitted from `/me` | Medium | Fixed | Increment 1.0, regression test |
| KI-19 | Security scanning | Java dependencies were invisible to Trivy (no lockfile) | Medium | Fixed | Gradle dependency locking (`a239681`) |
