# Increment 2.0 — mandatory follow-ups (conditions of acceptance)

Recorded 2026-11-08 from the product owner's conditional acceptance of Increment 2.0. These items must be addressed
**before examination-delivery implementation (Increment 2.5) begins**; item B must also be completed and tested before
any real examination delivery. They are tracked as work package **R** ("readiness for delivery"), to be scheduled as
its own authorized increment after 2.1–2.4 and before 2.5. Nothing here is implemented yet; no issue below is closed
or downgraded.

## A. Layered candidate rate limiting (KI-66)

### Problem

Session-code attempts are limited per candidate (10/min), per code (5 failed/min) and per source address (300/min).
A large hall behind one campus NAT address can legitimately produce more than 300 attempts per minute during a mass
check-in, and an attacker on the same network can exhaust the address bucket for everyone (collective lock-out).

### Design

Limits are applied by **who** is acting first and by **where** only as a coarse anomaly signal. An address limit must
never be the only reason a legitimate, authenticated candidate is refused.

| Layer | Key | Scope | Limit (default) | On breach |
|---|---|---|---|---|
| L1 Candidate | candidate-realm subject + organization | all delivery calls | 240/min; check-in attempts 10/min | 429 for that candidate only |
| L2 Code | keyed hash of the presented code | failed attempts | 5 failed/min, 20 failed/day → code locked, staff can re-issue | 409 code_not_valid / locked |
| L3 Device/session | device key fingerprint (2.5: attempt-bound) or token `sid` | check-in and attempt calls | 30/min failed per device | 429 for that device only |
| L4 Session (exam) | readiness/exam session id | failed attempts across all candidates | adaptive: alarm at > 3× the expected failure rate of its roster size | operator alert + incident, **no automatic block** |
| L5 Address (anomaly) | source address | **failed** attempts only, and only from accounts with no successful check-in in the session | 300 failed/min | progressive delay (not refusal) for *new* accounts from that address; established candidates unaffected |
| L6 Global safety | organization | all delivery calls | 120,000/min | 429 + alert |

Additional rules:
* **Allow-listed exam venues:** an organization can register its venue addresses per session; L5 is then reporting-only
  for that session.
* **Successful check-in raises trust:** after a candidate has checked in once, address-based throttling never applies to
  that candidate in that session.
* **Brute force stays expensive:** codes have about 49 bits of entropy, are bound to a session window and (per-candidate
  codes) to a roster entry; L2 and L1 bound the guess rate per code and per account, and account creation is itself
  rate-limited and email-verified at the identity provider.
* Every refusal and throttle stays audited; alerts go to the operator dashboard (B).

### Tests to prepare (acceptance for A)

1. **Shared address, legitimate load:** 1,000 virtual candidates from **one** source address check in within 2 minutes
   (and a 500-candidate variant within 60 s) using valid codes → 100 % succeed, no 429 for any candidate who has not
   exceeded its own limits; p95 check-in latency recorded.
2. **Shared address with attackers:** the same 1,000 candidates plus 20 attacker accounts on the same address
   guessing codes at maximum rate → attackers are throttled per account, per code and per device; legitimate candidates
   still 100 % succeed; L4/L5 alerts raised; audit shows refusals for attackers only.
3. **Distributed guessing:** 200 addresses × 5 accounts guessing one session → per-code locks and the L4 alarm trigger;
   the expected number of successful guesses stays below 10⁻⁶ per session (calculated from limits and entropy).
4. **Venue allow-list:** with the venue address registered, test 2 shows no address throttling at all; attackers still
   blocked by L1–L3.
5. **Valkey unavailable:** limiter fails open with a metric; candidate-level limits recover on restart (existing
   behaviour, re-verified).

The harness (`backend/devtools/harness/DeliveryHarness.java`) gets an attacker mode and an "all from one address" mode;
results are published in `docs/performance/` like the 2.0 runs.

## B. Outage detection beyond total failure (KI-71, KI-72, KI-73)

Must be completed and tested before real examination delivery.

| Capability | Design |
|---|---|
| **Per-instance health** | Each delivery instance reports heartbeats with instance id, version, start time, in-flight requests, error rate (1 min), p95 latency and dependency checks (database, Valkey, identity provider keys). Stored per instance with history (rolling 24 h). |
| **Partial failure detection** | Detector rules per instance and per role: instance silent > 45 s; instance error rate > 5 % for 2 min; p95 > 2 s for 2 min; dependency check failing; share of healthy instances < configured minimum. Each rule opens or updates a platform incident with severity (instance-degraded, role-degraded, role-down). |
| **Candidate/session impact** | From 2.5 every request carries the attempt id and the serving instance; impact = attempts whose requests failed, timed out or went silent (no heartbeat > 45 s) during the incident window, plus candidates whose check-in failed in the window (closes KI-73 together with session rosters). Lost time per attempt is computed from the attempt journal (verified lost time for Q-06). |
| **Operator dashboard** | Platform-operator screen (platform role only): live instance list with health, active incidents with timeline, affected organizations/sessions/attempt counts, acknowledgement and notes, links to logs and runbooks. Organizations keep seeing only their own impact. |
| **Correlation and recovery** | Incidents correlate signals by time window and instance/role (one incident for one cause); recovery is declared when all rules are clear for 2 min; the incident keeps start, detection, mitigation and recovery times; post-incident report template. |
| **Compensation integration** | Each organization impact feeds the configurable compensation policy (Q-06): verified lost time per attempt, recovery buffer, rescheduling option; proposals still require independent approval (ADR-0007) and are fully audited. |

Tests (acceptance for B): kill one of three delivery instances (role stays up, instance incident only, affected
attempts listed); inject 10 % errors on one instance (degraded incident); slow database on one instance; full role down
(existing test); network partition of one instance (staging network); correlation of two simultaneous signals into one
incident; dashboard visible only to platform operators; compensation proposal fed with verified lost time.

## C. Answer persistence — mandatory requirements for Increment 2.5

Recorded as architecture and acceptance requirements in `07-examination-delivery.md` §"Answer persistence
requirements (mandatory)" and in the roadmap gate for 2.5:

1. Every answer operation carries a **unique idempotency key** (attempt, item, client operation id).
2. The server **acknowledges only after durable persistence** (committed to the database journal).
3. **Duplicate retries** of the same operation are recognised and answered with the original acknowledgement; they never
   create a second effect.
4. **Sequence-based protection**: a per-item, per-attempt monotonic client sequence; an operation with a lower sequence
   than the stored one is recorded in the journal but never overwrites the newer answer.
5. **Reconciliation** of the device's offline queue with server-persisted answers on reconnect and on resume (server
   returns the persisted sequence per item; the client re-sends only what is missing; conflicts are resolved by
   sequence, never by arrival time).
6. The candidate sees for every answer whether it is **saved** (server-acknowledged), **pending** (queued on the device)
   or **failed** (needs attention); a locally queued answer is **never** shown as saved.
7. **Recovery testing** for database stalls, network loss and delivery-process crashes, with zero lost acknowledged
   answers (the 2.0 harness already measures acknowledged versus stored saves).

## D. Issue register KI-66 … KI-73

Also mirrored in `../known-issues.md`. "Blocks real exam delivery" means the issue must be resolved (or the stated
condition met) before real candidates sit a real examination.

| ID | Description | Severity | Business impact | Root cause | Proposed resolution | Target | Blocks real exam delivery |
|---|---|---|---|---|---|---|---|
| KI-66 | Session-code attempts limited per source address (300/min); a large hall behind one campus NAT can hit it, and an attacker on the same network could exhaust it for everyone | Medium | Mass check-in delays or refusals at a venue; reputational risk on exam day | Address used as a primary limiter key | Layered limiting (A): identity, code and device first; address only for unestablished accounts' failures, with venue allow-lists; tests with 500–1,000 candidates on one address plus attackers | Work package R (before 2.5) | **Yes** |
| KI-67 | After a candidate password reset, other sessions end within ~10 s (platform-enforced) and existing access tokens remain valid for up to 5 min | Low | A stolen session may live briefly after the owner resets the password | Keycloak 26.8 signs out other devices only when the candidate ticks the box | Keep RecoveryGuard; in 2.5 bind attempt tokens to the device key and check a per-candidate session generation on each delivery call so revocation is immediate | 2.5 | No (yes for attempt tokens: immediate revocation is part of the 2.5 gate) |
| KI-68 | Candidate-realm behaviour (email-first registration, reset requiring the second factor, event log) depends on Keycloak 26.8 configuration | Low | A Keycloak upgrade or manual realm change could silently weaken recovery | Behaviour lives in realm configuration, not code | Realm-as-code review on every change; exam E2E suite run on every Keycloak upgrade (CI); add a startup self-check of the reset-flow configuration through the admin API | R | No (yes if the self-check fails) |
| KI-69 | Exam-app tokens live only in memory, so a full page reload needs a single-sign-on redirect | Info | Brief redirect after a reload; no data loss in 2.0 | Deliberate C-4 choice (no tokens in storage) | Keep; 2.5 keeps attempt state server-side and resumes after redirect; measure resume time in the 2.5 gate | 2.5 (verify) | No |
| KI-70 | Deferred: email digest, summary events for bulk operations, per-organization fairness in the outbox publisher | Low | One organization's bulk import can delay another's notifications by seconds | Scope deferral | Fair claiming (round-robin by organization), summary events for imports, digest option | 2.4 | No |
| KI-71 | Only whole-role delivery outages are detected automatically; single-instance or partial failures are not | Medium | Candidates on a failing instance could lose time without an incident or compensation proposal | 2.0 detector design | Outage design B (per instance, partial, degraded rules, correlation) | R | **Yes** |
| KI-72 | No platform-operator incident screen; operators rely on logs and organization views | Medium | Slower diagnosis and communication during an incident | Not built in 2.0 | Operator dashboard (B), platform role only | R | **Yes** |
| KI-73 | Candidates who could not check in at all during an outage are not identified as affected | Medium | Some affected candidates may be missed for compensation | Impact computed from presence and check-ins only; no roster yet | Rosters (2.3/2.4) and attempt-level impact (B, 2.5): expected roster minus successful check-ins in the window | R / 2.5 | **Yes** |
