# Layered check-in limits — shared-address and attacker runs (KI-66, follow-up A)

Recorded 2026-11-09 on branch `ws-c/m3c-delivery-hardening` by `LayeredCheckInLimitsIT` (Testcontainers PostgreSQL and
Valkey, delivery process role, MockMvc in-process, 32–48 client threads). Raw results are written to
`backend/api/build/reports/ki66/*.json` on every run. These are functional acceptance runs in a single JVM; they are not a
substitute for the HTTP load harness (`backend/devtools/harness/DeliveryHarness.java`), which still has no attacker or
single-address mode (open item below).

Test limits (defaults in brackets): address 100 failed/min (300), code 25 failed/min (5) and 20 failed/day (20),
expected-failure ratio 0.02 (0.05), address delay 5 ms per excess attempt up to 50 ms (250 ms / 5 s).

| Run | Candidates | Result | Wall clock | Check-in latency |
|---|---|---|---|---|
| One address, valid codes | 500 | 500/500 succeeded, 0 × 429 | 1.0 s (budget 60 s) | p50 61 ms, p95 80 ms, max 108 ms |
| One address, valid codes | 1,000 | 1,000/1,000 succeeded, 0 × 429 | 2.3 s (budget 120 s) | p50 70 ms, p95 101 ms, max 154 ms |
| One address + 20 attackers (30 guesses each, half on one shared guess) | 1,000 | 1,000/1,000 succeeded; attackers: 600 attempts, 200 past L1, 0 successes; shared guess refused at the code layer; address alert and L4 alarm raised; every DENIED audit row belongs to an attacker | 3.4 s | legitimate p95 149 ms |
| Same, venue network `203.0.113.0/24` registered | 200 | all succeeded; no delays at all; alert marked `venue: true`; attackers still limited per account | — | — |
| Distributed: 200 addresses × 5 accounts, 4 attempts each (one on a real code not yet valid) | 1,000 accounts | 0 successes, 0 × 429, every answer the uniform 409 `code_not_valid`; the real code locked after 20 failures (audited once); L4 alarm raised; no address alert | — | — |

Expected successful guesses (test 3): every account fails at most `failedPerCandidatePerDay` (30) times a day, each guess
hits one of the session's codes with probability codes / 31¹⁰. For 1,000 accounts and 1,000 issued codes:
1,000 × 30 × 1,000 / 31¹⁰ ≈ **3.7 × 10⁻⁸** per day (< 10⁻⁶). The bound grows linearly with the number of accounts an attacker
controls; it stays below 10⁻⁶ up to about 27,000 verified accounts per 1,000 codes.

Test 5 (Valkey unavailable): `RateLimiterFailOpenIT` stops a dedicated Valkey container: every check is allowed, counters
return 0 and alert flags never fire, the `ivorovi.ratelimit.failopen` metric counts each call; after the container is
started again the per-candidate limit (10/min) applies again.

Open: harness attacker and one-address modes over real HTTP (follow-up A asks for them); a run with production default
delays.
