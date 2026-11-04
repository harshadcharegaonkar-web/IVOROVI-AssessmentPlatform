# Increment 1.7 — known issues in detail (KI-59 to KI-64)

Severity scale used across the project: **Release blocker** (must be fixed before real data in production),
**High**, **Medium**, **Low**, **Info**. Summary rows remain in `docs/known-issues.md`.

## Register (updated 2026-11-04)

| Issue | Severity | Status | Business impact (short) | Resolution / target | Owner |
|---|---|---|---|---|---|
| KI-59 | High | **Fixed** 2026-11-04 | Machine access could outlive withdrawn authority | Live revalidation, owner-suspension workflow, service identities (see below) | Engineering |
| KI-60 | Info | Accepted (by design) | No automatic SIS/LMS data exchange yet | Mapping layer + first approved connector: Phase 2 increment 2.14; vendor scope decision pending | Product (scope), Engineering |
| KI-61 | Low | Open, scheduled | Duplicate read-only health checks with several API instances | Database lease for scheduled checks: increment 2.0 (foundations) | Engineering |
| KI-62 | Info | Accepted (contract) | Receivers must de-duplicate | Guidance published; per-resource sequence numbers with the Phase 2 event catalogue (2.14) | Engineering |
| KI-63 | Info | Accepted, change approved | Alerts only in-app | Email channel with preferences: increment 2.0 (decision P2-09 approved with the blueprint) | Engineering |
| KI-64 | Low | Open, scheduled | Webhooks/notifications lag during bulk operations | Parallel outbox publisher (≥ 2,000 events/s) and summary events: increment 2.0; separate exam-event channel with delivery (2.5) | Engineering |

## KI-59 — API client scopes are only checked at registration

* **Severity:** High (raised from Low at the 1.7 review: it is an authorization defect, not an inconvenience).
* **Business impact:** a former administrator's integration, or one whose owner lost a permission, could keep reading
  data the organization intended to withdraw — for example student records after an administrator moved to another
  college. Organizations cannot rely on role changes to cut off machine access.
* **Resolution (implemented 2026-11-04):** live permission revalidation on every request, service identities for
  long-running integrations, and an owner-suspension workflow (`architecture/10-api-client-authorization.md` §8).
  Evidence: `ApiClientAuthorizationIT` (9 security tests), E2E `ki59.spec.ts`, benchmark
  `performance/api-key-authorization-benchmark.md`, runbook `runbooks/service-identities.md`.

## KI-60 — The generic REST adapter's sync checks connectivity only

* **Severity:** Info (by design).
* **What happens:** “Sync now” calls the configured data endpoint and records status, size and duration. No records
  are read into Ivorovi or written out.
* **Business impact:** institutions cannot yet exchange student, enrolment or result data with a SIS, ERP or LMS
  automatically; data still moves through the 1.6 bulk import and the 1.7 report exports. Administrators could mistake
  a successful “sync” for a data transfer — the UI and run message say “connectivity only; no data imported”.
* **Proposed resolution:** Phase 2 integration architecture (`phase-2/11-integration-architecture.md`): a mapping
  layer (canonical student/enrolment/result schema, field mapping, dry-run, conflict policy, audit per record) and the
  first approved connector (recommended: OneRoster 1.2 / LTI 1.3 for LMS; SIS connectors per approved vendor). Requires
  an approved vendor scope (product decision).

## KI-61 — Scheduled health checks run on every API instance

* **Severity:** Low.
* **What happens:** each API replica runs the 15-minute health check loop, so with three replicas an integration may be
  checked three times per interval.
* **Business impact:** extra, harmless read-only calls to the external system; duplicate “Scheduled” rows in the run
  history; slightly more load on rate-limited partner APIs. No data effect.
* **Proposed resolution:** claim due checks with a database lease (`FOR UPDATE SKIP LOCKED` on a `next_check_at`
  column, the pattern already used by imports and the webhook dispatcher) so exactly one replica runs each check.
  Effort: ½ day; can be done with the KI-59 work or in the first Phase 2 increment.

## KI-62 — Webhooks are delivered at least once

* **Severity:** Info (industry-standard contract).
* **What happens:** after a timeout, an endpoint may have processed a delivery that Ivorovi then retries; replays send
  the same event again on purpose. Ordering across events is not guaranteed.
* **Business impact:** a receiver that does not de-duplicate could, for example, create a duplicate LMS enrolment.
* **Proposed resolution:** keep the contract and make it easy to honour: every delivery carries the event `id` and
  `Ivorovi-Delivery-Id` (done); publish receiver guidance and a verification snippet (done in `api/examples-1.7.md`);
  add a per-endpoint sequence number per resource in Phase 2 so receivers can discard out-of-order updates.

## KI-63 — Notifications are in-app only

* **Severity:** Info (requirement: no new channels without approval).
* **Business impact:** people learn about failed imports, disabled webhooks or role changes only when they open
  Ivorovi; time-critical alerts (e.g. a failing integration overnight) can be missed.
* **Proposed resolution:** Phase 2 notification channels behind approval: email (already used for invitations, so
  no new infrastructure), per-user preferences, digest mode, and quiet hours; SMS/WhatsApp only with an approved
  provider and DPDP review. Data model already carries `channel` and `delivery_status`.

## KI-64 — Webhooks and notifications inherit outbox lag under load

* **Severity:** Low.
* **What happens:** the outbox publisher sends 100 events per second-long poll (KI-58). A 10,000-row import emits
  ~60,000 events, so webhook deliveries and notifications can lag by minutes. Nothing is lost.
* **Business impact:** external systems and users see changes late during bulk operations; not acceptable later for
  examination events (e.g. live proctoring alerts).
* **Proposed resolution:** (1) publish in parallel batches with per-tenant fairness and raise the batch size
  (measured target: ≥ 2,000 events/s); (2) let bulk operations emit one summary event per batch instead of one per row
  where consumers allow it; (3) for examination delivery in Phase 2, use a dedicated low-latency channel that does not
  share the general outbox. Scheduled as a Phase 2 foundation item (before the examination engine).
