# 11 — Phase 2 foundations (Increment 2.0)

Shared foundations every later Phase 2 increment builds on. No question bank, assessment, scheduling or exam logic yet.
Decisions: ADR-0007 (four-eyes), ADR-0008 (candidate identity and exam app security); register `phase-2/decision-register.md`.

## 1. Processes

```
                     staff browser (web app, :8081 / dev :5173)            candidate browser (exam app, :8082 / dev :5174)
                                │  staff realm token (aud ivorovi-api)                │  candidate realm token (aud ivorovi-delivery)
                                ▼                                                     ▼
   ┌──────────── ivorovi-api.jar, IVOROVI_PROCESS_ROLE=ADMIN ─┐      ┌── ivorovi-api.jar, IVOROVI_PROCESS_ROLE=DELIVERY ──┐
   │ /api/v1/** except delivery (delivery paths → 404)       │      │ only /api/v1/delivery/** + health (rest → 404)       │
   │ outage detector, recovery guard, email dispatcher,      │      │ heartbeat every 10 s                                 │
   │ outbox publisher, schedulers (all leased)               │      │ own pool, own rate limits, CORS = exam origin only   │
   └──────────────────────────┬──────────────────────────────┘      └──────────────────────────┬───────────────────────────┘
                              └────────────── PostgreSQL (RLS) · Valkey · RabbitMQ ─────────────┘
```

* Same build, two roles (P2-03); `ProcessBoundaryFilter` runs before security.
* Scheduled work that must run once uses `JobLeases` (`job_lease` table: atomic upsert when expired or owned).

## 2. Modules and data

| Module | Tables | Notes |
|---|---|---|
| `approvals` | `approval_request` | four-eyes framework; handlers per type (`exam.compensation`, test type `test.change`) |
| `exams` | `exam_portal`, `candidate_account`, `readiness_session`, `session_code`, `check_in`, `delivery_presence`, `exam_incident_impact`, `exam_incident_candidate`, `exam_compensation`, `candidate_compensation` | all tenant-owned with RLS; candidate email encrypted (`FieldCipher`), codes as keyed HMAC |
| platform | `process_heartbeat`, `platform_incident` (platform-only RLS; organizations may read incidents that affected them), `job_lease`, `email_delivery`, `notification_preference` | |

Migrations: `V202611081100` (distinct custodians), `V202611101000` (leases, email), `V202611101100` (approvals),
`V202611101200` (exams), devdata `V202611101300` (synthetic second exam controller, dev only).

## 3. Main flows

**Check-in.** Portal key → organization (security-definer lookup, cached 30 s) → candidate token validated (issuer,
key, audience, verified email) → candidate account linked → MFA policy → rate limits → one atomic
`UPDATE session_code … WHERE consumed_at IS NULL AND window AND portal RETURNING` → `check_in` → audit. Refusals are
audited after the transaction (no second pooled connection).

**Outage → compensation.** Heartbeats stop for 45 s → platform incident; organizations with open sessions get an entry
and a notice → heartbeats resume → incident resolved, affected candidates recorded per organization → proposal by an
exam controller → approval request → second controller approves → bound single-use entitlement per candidate +
email → candidate uses it once from the portal.

**Recovery.** Password reset needs email + second factor; afterwards every session of the candidate ends (Keycloak or
`RecoveryGuard`); lost factor → audited staff reset.

**Events.** Outbox → 4 parallel publisher workers, 500-event batches, one confirm per batch. Email notifications are
queued with the in-app notification (preference per category), sent by a leased dispatcher with retries
(1, 5, 15, 60, 60 min; FAILED after 6 attempts); in-app delivery never depends on email.

## 4. Verification

Integration tests: `ApprovalsIT`, `DeliveryIT` (delivery role), `ExamAdminIT`, `IncidentWorkflowIT`, `ReliabilityIT`.
E2E: `frontend/apps/exam/e2e` (real candidate realm). Load and failure: `backend/devtools/harness/DeliveryHarness.java`,
`scripts/benchmark/delivery-failure-tests.sh`, `scripts/benchmark/outbox_benchmark.py`; results in `docs/performance/`.
