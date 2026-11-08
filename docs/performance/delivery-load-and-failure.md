# Delivery load and failure results (Increment 2.0-F)

Raw results: `delivery-harness-2000.json` (load), `delivery-failure-tests.json` (failures). Machine: development
container, 4 vCPU, 15 GB RAM; Docker Compose infrastructure on the same machine; one delivery process (`-Xmx1g`,
virtual threads, pool 20). The harness, the delivery process, the administrative API and all containers share the 4 cores,
so these are conservative single-machine numbers, not production capacity.

## Harness (repeatable, JDK only, Q-11)

`java backend/devtools/harness/DeliveryHarness.java --candidates 2000 --duration PT10M --ramp PT60S --heartbeat PT15S --save PT10S --pid <delivery pid>`

The harness serves its own JWKS and signs candidate-realm-shaped tokens; the delivery process under test is started with
`IVOROVI_DELIVERY_OIDC_ISSUER/JWKS` pointing at it (configuration only — the production validation chain runs:
issuer, signature, audience, verified email). Setup goes through the real staff API (portal with MFA off, session, one
single-use code per candidate). Each virtual candidate signs in (enrolment), checks in with its own code, then sends a
presence heartbeat every 15 s and an acknowledged practice save every 10 s (200-byte payload). All candidates come from
one address, so the per-address code limit is raised for the run (`IVOROVI_DELIVERY_CODE_ATTEMPTS_PER_IP=100000`).

## Load: 2,000 virtual candidates for 10 minutes (plus 60 s ramp)

| Endpoint | Requests | p50 ms | p95 ms | p99 ms | max ms | req/s |
|---|---|---|---|---|---|---|
| GET /me (enrolment, during ramp) | 2,000 | 28.4 | 751.3 | 1,100.9 | 2,120.2 | 3.0 |
| POST /check-ins (single-use code) | 2,000 | 32.1 | 626.6 | 1,090.2 | 1,583.8 | 3.0 |
| POST /presence | 83,954 | 11.9 | 39.1 | 177.6 | 2,070.3 | 127.2 |
| POST /saves | 125,924 | 12.0 | 39.3 | 185.7 | 2,773.5 | 190.8 |

* Total 213,878 requests (324 req/s sustained), **0 errors**; 2,000/2,000 checked in.
* Saves: 125,924 acknowledged, 125,924 stored by the server → **0 lost acknowledgements**.
* Delivery process: CPU 28 % average / 40 % peak of the 4 cores, resident memory ≤ 745 MB, 55 threads (virtual threads
  serve the requests). PostgreSQL 16–42 % of one core, Valkey 6–17 %.
* The higher sign-in/check-in tails come from the 60 s ramp (2,000 first requests while the JIT warms up), not from
  steady state.

## Failure tests (`scripts/benchmark/delivery-failure-tests.sh 200`)

200 virtual candidates (heartbeat and save every 5 s, ≈ 80 req/s) for 6 minutes:

| t (s) | Event | Effect |
|---|---|---|
| 60 | Valkey restarted | **0 errors** (rate limiter fails open; caches refill) |
| 101 | RabbitMQ restarted | **0 errors** (delivery requests do not depend on the event bus) |
| 140 | PostgreSQL paused 10 s | 23 requests timed out (10 s client timeout), mean latency 1.6 s in that window; full recovery immediately after |
| 180 | Delivery process killed (SIGKILL) | every call refused while down |
| 227 | — | **outage detected automatically after 47 s** (threshold 45 s): platform incident + organization entry opened |
| 287 | Delivery restarted | requests succeed again (first window mean 310 ms, then normal) |
| 303 | — | **incident resolved 16 s after restart**; 200 of 200 checked-in candidates recorded as affected |

* After the run: administrative API and delivery healthy; the incident is ready for a compensation proposal
  (the complete proposal → four-eyes → entitlement path is covered by `IncidentWorkflowIT` and the exam E2E).
* 9 saves were stored by the server but their acknowledgement never reached the client (in-flight during the database
  stall and the crash). This is the expected "lost acknowledgement" case: answer saves in 2.5 must be idempotent and
  retried with the same sequence number (`last_save_seq` is already kept per candidate and session).
* Not exercised here: network partition of a single client (no iptables in the dev container) — covered by the
  client-side reconnect loop and the presence view; to be repeated on a staging network in 2.5.
