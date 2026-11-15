# Production-like load-test environment (decision D-14)

**For:** DevOps, to provision. **Owner of the test:** engineering (primary session).
**Status:** requested 2026-11-15. The test runs **within two working days** of this environment being handed over.

The single-machine runs of Day 4 are **not** production-scale validation. There, one shared 4-vCPU container hosted the
load generator, the API, the delivery process and all infrastructure; see `docs/performance/exam-harness-day4-*.json`.
This document defines what the real test needs.

## Target concurrency and pass thresholds (fixed before execution)

The targets come from the product requirements (`docs/phase-2/01-product-requirements.md`) and the delivery gate
(`07-examination-delivery.md`, `17-roadmap.md`).

| Scenario | Load | Pass thresholds |
|---|---|---|
| **S1 Event gate** | 2,000 concurrent candidates in one examination, arrivals within 5 min; 30 questions; 60 min | p95 answer save **< 300 ms**, p99 < 1 s; check-in p95 < 2 s; start p95 < 2 s; **zero** lost acknowledged answers; every attempt submitted exactly once; error rate (5xx and timeouts, excluding injected failures) < 0.1 % |
| **S2 Region peak** | 10,000 concurrent candidates across 5 examinations of 2,000; 50 answer saves/s per 1,000 candidates (500 saves/s total) | Same as S1 |
| **S3 Submission burst** | 10,000 submissions (candidate and automatic) within 2 minutes at the end of S2 | All 10,000 recorded exactly once within 2 minutes; zero lost answers; scoring completes within 15 minutes |
| **S4 Chaos (during S1)** | Kill one delivery instance (SIGKILL); restart Valkey; 30 s network drop for 10 % of candidates; PostgreSQL primary failover to the replica | Zero lost acknowledged answers; no candidate refused at check-in or start because of the failure (KI-91); recovery to normal latency within 60 s; failover p99 save during switch < 10 s |
| **S5 Shared address** | S1 with all candidates behind 1 address, then behind 4 addresses, with and without a registered venue network | No 429 for legitimate candidates; check-in p95 < 2 s with a registered venue network (the address-layer delay applies otherwise, by design) |

Each scenario is run twice; both runs must pass. The harness is `backend/devtools/harness/ExamHarness.java` (real
examinations), extended with multi-generator coordination and a database failover step before the run.

## Required infrastructure

The sizing below is an **initial estimate**. It is derived from the Day 4 runs: 1,000 candidates on 4 shared vCPUs, with
saves at p50 9 ms outside the outage window. S1 confirms or corrects it before S2.

| Component | Instances | CPU / memory per instance | Notes |
|---|---|---|---|
| Delivery process (`IVOROVI_PROCESS_ROLE=DELIVERY`) | 4, behind a load balancer | 4 vCPU / 8 GB, JVM heap 4 GB | Java 21; one instance is killed in S4 |
| Administrative process (staff API, scoring, auto-submit leases) | 2 | 4 vCPU / 8 GB | Scoring and auto-submit jobs are leased (one runner) |
| PostgreSQL 16 primary | 1 | 8 vCPU / 32 GB | NVMe SSD ≥ 500 GB, ≥ 10,000 IOPS; `max_connections` ≥ 400; `pg_stat_statements` enabled; production parameters (shared_buffers 8 GB, effective_cache_size 24 GB, wal_compression on) |
| PostgreSQL streaming replica | 1 | same as primary | For the S4 failover; promotion by the operator's tool (e.g. Patroni) |
| Valkey 8 | 1 primary + 1 replica (Sentinel) | 2 vCPU / 4 GB | Restarted in S4; no persistence needed for rate limits |
| RabbitMQ 4 | 1 | 2 vCPU / 4 GB | Outbox events |
| Keycloak 26.8 | 2 | 2 vCPU / 4 GB | Own PostgreSQL database (2 vCPU / 4 GB); candidate realm as in production |
| Object storage (S3-compatible) | 1 | 2 vCPU / 4 GB | Question images |
| Load generators | 3 (S2 needs about 3,400 virtual candidates each) | 8 vCPU / 16 GB | **Separate machines**, not on the system under test; JDK 21 |
| Monitoring | 1 | 4 vCPU / 8 GB | Prometheus and Grafana (JVM, PostgreSQL, Valkey, node exporters), log aggregation |

## Network

- **Load balancer:** HTTP/1.1 keep-alive, a 60 s idle timeout, and the same nginx proxy configuration as production for
  the exam application.
- **Bandwidth:** at least 1 Gbps between the load generators and the load balancer. Generator-to-service latency is
  recorded; it should be at least 10 ms to stay realistic.
- **Source addresses:**
  - S5 needs one generator behind **one NAT address**, then behind **four**.
  - The other scenarios use the generators' own addresses.
- **Failure injection:** `tc netem` (or the cloud equivalent) on the generator side for the S4 network drop.
- **Firewall:** generators reach only the load balancer and Keycloak; services reach each other on private addresses.

## Access, data and configuration

- **Data:** synthetic only. The harness creates its own organization data through the staff API; no production or
  student data, ever.
- **Credentials:** dev-realm-style staff users for setup, and the harness's own candidate-token issuer (configured on the
  delivery processes) **or** a dedicated load-test candidate realm. No production secrets in the environment.
- **Engineering access:** shell access to the generators, read access to monitoring, and the ability to SIGKILL one
  delivery instance and to trigger the database failover.
- **Configuration:** production configuration otherwise, including rate limits at production defaults; raised limits
  are reported if any are needed.

## What engineering delivers after the test

- The JSON reports of every run.
- Grafana snapshots: CPU, memory and GC per instance; database connections and slow queries; Valkey latency.
- A pass/fail table against the thresholds above.
- The defects found.
- The corrected sizing.
