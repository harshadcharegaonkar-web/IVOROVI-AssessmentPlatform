# API-key authorization under load (KI-59)

Run 2026-11-04 by `scripts/benchmark/api_key_auth_benchmark.py --seconds 20 --levels 1,8,32` against the local stack
(development container, 4 vCPU, 15 GB RAM, shared; API `java -jar`, dev profile). Benchmark organization only
(36,400 synthetic students). Endpoint `GET /api/v1/org-units/tree`. The per-user rate limiter was **disabled for the
measurement only** (it otherwise throttles a single credential at 300 reads/min, which is what made the first attempt
invalid). It was re-enabled afterwards. Raw data: `api-key-authorization-benchmark.json`. 0 errors in every run.

| Concurrency | Credential | Requests/s | p50 | p95 | p99 |
|---|---|---|---|---|---|
| 1 | Bearer token (baseline) | 284 | 3.3 ms | 5.0 ms | 7.8 ms |
| 1 | Delegated API key | 241 | 3.9 ms | 5.2 ms | 6.9 ms |
| 1 | Service-identity key | 259 | 3.7 ms | 4.9 ms | 6.8 ms |
| 8 | Bearer | 1,133 | 6.7 ms | 10.9 ms | 14.1 ms |
| 8 | Delegated | 835 | 9.1 ms | 13.8 ms | 17.5 ms |
| 8 | Service | 896 | 8.5 ms | 12.7 ms | 15.7 ms |
| 32 | Bearer | 1,482 | 20.4 ms | 35.7 ms | 44.2 ms |
| 32 | Delegated | 1,089 | 28.2 ms | 43.8 ms | 54.4 ms |
| 32 | Service | 1,115 | 27.5 ms | 43.5 ms | 54.3 ms |

## What it costs and why

Live revalidation adds ≈ 0.6 ms at p50 for a single client. Under 32 concurrent clients, API-key throughput is
≈ 75% of a cached bearer token. Each key request:
1. resolves the organization from the key id;
2. verifies the secret hash;
3. reads the owner's account status (or the service identity's status);
4. reads the owner's grants (shared cache) or the identity's role grants;
5. intersects them with the client's scopes and unit.

Authorization data is deliberately **not cached per client**, because that cache is exactly what KI-59 removes.

## Finding fixed during the benchmark

The first measurement showed API keys at ≈ 45% of bearer throughput under 32 concurrent clients. Cause: every
request wrote the client's daily usage counter synchronously, so concurrent requests queued on one row lock. Usage is
now counted in memory and written in batches every 10 s (`ApiClientUsage`), which brought throughput to ≈ 75%. Usage
figures can lag by up to 10 s. Authorization never depends on them.

## Further headroom (not needed now)

The organization lookup and the secret check could be combined into one statement, and the owner's account status
could be read through the shared principal cache with status-change eviction. Expected gain ≈ 1–2 ms per request at
high concurrency. Recorded for Phase 2 increment 2.0 performance work if API-client traffic grows.
