# Outbox publishing under bulk load (Increment 2.0-E, KI-64)

Date 2026-11-05. Machine: development container (4 vCPU class, Docker Compose stack: PostgreSQL 16, RabbitMQ 4.3), one
administrative API process with the default publisher settings (4 workers, 500-event batches, one publisher confirm per
batch, 1 s poll). Raw results: `outbox-throughput.json`.

| Measurement | Before 2.0 (KI-64) | 2.0, running stack (50,000 events) | 2.0, Testcontainers (`ReliabilityIT`, 20,000 events) |
|---|---|---|---|
| Sustained publishing rate | ≈ 100 events/s | **11,658 events/s** | ≈ 7,700 events/s |
| Time to drain the bulk | — | 4.3 s (+0.9 s until the first batch) | 2.6 s |
| Delivered to a bound queue | — | 50,000 (0 lost, 0 duplicated) | 20,000 (0 lost, 0 duplicated) |

Target in the plan: ≥ 2,000 events/s sustained — met with a wide margin. Semantics stay at-least-once (a batch whose
confirm fails is retried with backoff; consumers deduplicate by event id).

Reproduce: `python3 scripts/benchmark/outbox_benchmark.py 50000` (inserts the events for a synthetic organization in one
statement, measures until all are marked published, checks the queue, then removes the synthetic data).
Not yet done (KI-70): per-organization fairness in batch claiming and summary events for bulk operations.
