# 16 — Deployment architecture (SaaS, private cloud, on-premises)

One codebase and one set of images for all topologies (as in `architecture/01-product-architecture.md` §3).

## Runtime components

| Component | Image | Scales by | Notes |
|---|---|---|---|
| web (staff SPA) | ivorovi-web | requests | static + reverse proxy |
| exam (candidate SPA) | ivorovi-exam | CDN/static | separate origin recommended (CSP, cookie isolation) |
| api | ivorovi-api (profile `api`) | CPU/requests | modular monolith |
| delivery | ivorovi-api (profile `delivery`) | concurrent attempts | pre-scaled on exam days; no admin endpoints enabled |
| workers | ivorovi-api (profile `worker`) | queue depth | imports, results, scorecards, evaluation allocation |
| code-runner | ivorovi-code-runner | queue depth | isolated node pool (Kata/gVisor), no DB, no egress |
| vision-worker | ivorovi-vision | queue depth | CPU inference; optional GPU |
| ai-worker | ivorovi-ai-worker | queue depth | only outbound to approved providers or local model |
| PostgreSQL | managed or Patroni/CloudNativePG | — | HA primary + replica; PITR backups |
| Valkey | managed or sentinel | — | cache + hot attempt state |
| RabbitMQ | managed or quorum cluster | — | outbox, jobs |
| Object storage | S3/SeaweedFS/MinIO-compatible | — | documents, media, snapshots, scorecards |
| Keycloak | — | — | per-tenant realms or one realm with brokering (current: one realm) |
| ClamAV | clamd | — | RB-1 |
| Local LLM (optional) | vLLM/Ollama | GPU | on-premises AI |

## Topologies

| | SaaS (multi-tenant) | Private cloud (dedicated) | On-premises |
|---|---|---|---|
| Orchestration | Kubernetes (Helm), multi-AZ | Kubernetes in customer or Ivorovi-managed account | Kubernetes (Helm) or Docker Compose bundle for small installations |
| Tenancy | many tenants, RLS | one tenant (RLS still on) | one tenant |
| Code execution | Kata on KVM node pool | Kata or gVisor | gVisor (`runsc`) on dedicated hosts |
| AI | approved hosted providers / Ivorovi-hosted | customer account | local model or disabled |
| Updates | continuous, exam-day freeze | scheduled windows | signed release bundles, air-gapped installer |
| Licence | subscription, metered | subscription | signed offline licence file |

## Exam-day operations

Exam calendar drives **pre-scaling** of delivery pods and database capacity; change freeze 24 h before and during
events; synthetic candidate probes every minute; runbook for degraded modes (Valkey loss → DB-only path; queue backlog →
evaluation delayed, delivery unaffected); on-call with escalation.

## Observability

OpenTelemetry traces and metrics (already in Phase 0 kernel), SLO dashboards: save latency, ack lag, submission
success, disconnection rate, queue depths, runner verdict latency, AI latency/cost; centralised logs without personal
data; audit trail separate from logs.

## Backup and DR

PITR for PostgreSQL (RPO ≤ 5 min SaaS), object storage versioning, encrypted backups with the data key escrow
(RB-2), quarterly restore drills; RTO 1 h SaaS. During a live exam the candidate client's local queue covers short
outages; long outages trigger the incident procedure (session pause + deadline extension).
