# Dashboards, reports, API keys and webhooks — benchmark (increment 1.7)

Run 2026-11-04 on the local development stack (Docker Compose PostgreSQL, Valkey, RabbitMQ, Keycloak; API `java -jar`,
dev profile) by `scripts/benchmark/insights_benchmark.py --runs 15 --webhooks 1000`. Organization: the separate
benchmark organization of the import benchmark with **36,400 synthetic students** (Meridian and the test organizations
untouched). Organization administrator token (tenant-wide scope), sequential requests, latency measured at the client.
Raw results: `insights-integrations-benchmark.json`.

## Dashboards (15 runs each)

| Endpoint | p50 | p95 | Notes |
|---|---|---|---|
| `insights/executive` | 122 ms | 587 ms | p95 is the first (cold) request |
| `insights/academic` | 199 ms | 581 ms | |
| `insights/students` | 772 ms | 873 ms | 14 aggregate queries incl. three 12-month series over 36,400 students |
| `insights/staff` | 14 ms | 36 ms | |
| `insights/imports` | 17 ms | 33 ms | percentiles over all import jobs |

## Reports (first page of 25 unless stated)

| Report | p50 | p95 |
|---|---|---|
| students, first page | 537 ms | 578 ms |
| students, last page (page 1,456) | 558 ms | 637 ms |
| students, status filter + name sort | 435 ms | 458 ms |
| enrolments | 872 ms | 1,041 ms |
| imports | 15 ms | 21 ms |
| **export** students CSV, all 36,400 rows (5.8 MB) | 606 ms | 650 ms |
| **export** students XLSX, all 36,400 rows (2.3 MB) | 920 ms | 1,022 ms |

Paged reports pay mostly for the exact `count(*)` over the joined, scoped rows; exporting everything costs about as much as
one page. Acceptable for interactive use at this size; if organizations grow ×10, cache counts or use keyset paging
(recorded as an optimisation, not a defect).

## API-key authentication (200 sequential requests to `GET /api/v1/org-units/tree`)

| Credential | p50 | p95 | max |
|---|---|---|---|
| Bearer token (cached principal) | 10.7 ms | 15.0 ms | 25.0 ms |
| API key (organization lookup + hash comparison + daily usage upsert) | 11.8 ms | 15.8 ms | 28.0 ms |

Overhead ≈ 1 ms per request.

## Webhook delivery

1,000 deliveries queued for one endpoint, delivered by the scheduled dispatcher (4 parallel senders, batches of 50,
draining up to 20 batches per 5-second poll) to a local receiver that verified every HMAC signature:
**1,000/1,000 delivered with valid signatures in 35.7 s ≈ 28 deliveries/s** (including the wait for the first poll).
Before this increment's change the dispatcher sent one batch per poll (≈ 10/s). Throughput scales with
`ivorovi.integrations.webhooks.concurrency` and receiver latency.

Reference machine: development container, 4 vCPU, 15 GB RAM (shared); figures are indicative, not a capacity commitment.
