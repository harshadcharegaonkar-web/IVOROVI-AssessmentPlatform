# WS-C requests to Session A (cross-module changes and decisions)

Branch `ws-c/m3c-delivery-hardening`. Each item is either a change already on the branch outside the exams module (needs
A's review) or a change WS-C needs and did not make.

## Changes on the branch outside `com.ivorovi.exams` (review needed)

| # | File | Change | Why |
|---|---|---|---|
| R-1 | `platform/ratelimit/RateLimiter.java` | New `acquire(key, capacity, window, consume)`, `increment(key, ttl)`, `once(key, ttl)`; all fail open with the existing metric | Daily buckets (L1, L2), per-minute counters (L4) and once-per-minute alerts |
| R-2 | `platform/ratelimit/RateLimitFilters.java` | `FailedAuthentication(..., refuse)`; the delivery chain uses `refuse=false` (failures counted under `authfail-report:`, never blocks). Staff chain unchanged | KI-66: a pre-authentication address block let anyone on a campus network lock out every candidate behind the NAT address (signed tokens cannot be guessed) |
| R-3 | `platform/security/DeliveryFilters.java`, `DeliverySecurityConfiguration.java` | Unauthenticated delivery lookups: per-address ceiling `publicPerIpPerMinute` (default 20,000, was `max(public-per-ip, 60)` = 60); unknown portal keys limited separately (`portalMissesPerIpPerMinute`, 60), previously not limited at all; candidate bucket keyed by organization + subject | A 1,000-candidate hall loading the portal page from one address was refused after 60 requests |
| R-4 | `platform/config/DeliveryProperties.java` | Two new components (above). `codeAttemptsPerIpPerMinute` now means failed attempts of accounts without a check-in → delay, not refusal (env `IVOROVI_DELIVERY_CODE_ATTEMPTS_PER_IP` keeps its name) | KI-66 L5 |
| R-5 | `platform/process/*` | `ProcessHeartbeat` reports version, in-flight, 1-minute requests/errors/p95 and dependency checks and writes `process_heartbeat_sample`; new `RequestStats` + `RequestStatsFilter` (`/api/*`, order HIGHEST+6), `DependencyCheck` (database, valkey, identity_keys for DELIVERY) | KI-71 per-instance health. Heartbeat stays disabled in tests |

Process boundary (`IVOROVI_PROCESS_ROLE`), candidate authentication, tenant binding and the staff chain are unchanged.

## Requests (not made by WS-C)

| # | Request | Why |
|---|---|---|
| Q-1 | Dedicated platform permission for the operator incident view (e.g. `platform.operations.read` / `.manage`, `Availability.PLATFORM`, granted to `PLATFORM_SUPER_ADMIN` or a new `PLATFORM_OPERATOR` role) | KI-72: the only platform permission today is `platform.tenant.manage`; the planned operator API would gate on it until a dedicated one exists |
| Q-2 | Scheduled enrolments (M2) to provide a `com.ivorovi.exams.SessionRoster` bean returning the candidate-account ids expected in a session | KI-73: expected-but-not-checked-in candidates; today the expectation is the count of issued codes. The interface is on the branch; scheduling depending on exams avoids a cycle |
| Q-3 | Harness (`backend/devtools/harness/DeliveryHarness.java`) attacker and one-address modes over real HTTP | Follow-up A asks for them; ITs cover the behaviour, not HTTP-level load |
