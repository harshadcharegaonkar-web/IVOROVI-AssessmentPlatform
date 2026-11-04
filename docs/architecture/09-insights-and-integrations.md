# 09 — Dashboards, reporting, notifications and integrations (increment 1.7)

Status: implemented in increment 1.7 (awaiting review). Modules: `insights` (dashboards, reports), `notifications`
(event-driven producer, notification center), `integrations` (adapters, API clients, webhooks); platform additions in
`platform.outbox` (`IntegrationEvent`, actor on every event) and `platform.security` (API-key authentication port).

## 1. Principles

* **Every figure comes from the records** at request time — no stored aggregates that could drift, no static data.
* **Scoping is the same as the record lists.** A figure is computed only when the viewer holds the underlying read
  permission, and only over the units where they hold it (ltree subtree of their grant roots; tenant-wide grants see
  the whole organization; RLS isolates organizations underneath). A section the viewer may not see is omitted
  (`null`), never shown as zero.
* **No invented metrics.** Workload, performance or engagement indicators are not shown because the data model does not
  record them yet (staff dashboard says so).
* **Integrations never affect core availability.** Outbound calls run outside database transactions, with a 5-second
  timeout, on bounded thread pools or the caller's request; failures are recorded, never thrown into business flows.
  Event consumers run after the business transaction committed (outbox → RabbitMQ).

## 2. Dashboards (`/api/v1/insights/*`)

| Endpoint | Needs | Content |
|---|---|---|
| `executive` | any | Sections, each only with its permission: organization units (`org_unit.read`), programs/courses/subjects/active years/active batches (`academics.read`), students, active students, admission records, admissions in 30 days (`student.read`), staff/active/faculty (`staff.read`), active users and pending invitations (`user.read`), import activity (`student.import`) |
| `academic` | `academics.read` | Programs by level and institution, courses per program, subjects by category, batches by status and academic year, current enrolment by academic year and semester (needs `student.read`), faculty assignment overview (offerings with and without faculty, active assignments, staff teaching, uncovered offerings by department) |
| `students` | `student.read` | Totals per lifecycle status, admissions in 30 days, 12-month series of admissions, initial enrolments and progressions/promotions (zero-filled), enrolment changes in the last year by type, current enrolment by program, course, batch and semester |
| `staff` | `staff.read` | Totals per employment status, linked sign-in accounts, active staff by category, department, campus and employment type, faculty assignment summary (active faculty, teaching, without assignment, assignments, primary) |
| `imports` | `student.import` | Jobs by outcome, in progress, awaiting action, records processed/created/updated/rejected, average/median/95th-percentile duration and rows per second, jobs per month, 10 most recent jobs; optional date range |

All accept `orgUnitId` (subtree filter, intersected with the viewer's scope). Staff are scoped through their
organizational assignments (`EXISTS` over `org_assignment`), students through their scope unit, academic records
through their owning unit/department, imports through their institution (`InsightsScope`).

## 3. Reporting (`/api/v1/reports`)

A registry of report specifications (`ReportService`): key, columns (personal columns flagged), filters, sort keys,
date column, statuses, the read permission of the records and the scoping path. Reports: `students`, `enrolments`,
`staff` (no contact details), `batches`, `imports`.

* Needs `report.read` **and** the record permission; rows scoped exactly like the lists.
* Filters: date range, organization unit, academic year, program, course, batch, status (validated per report); sort
  keys are whitelisted; server-side pagination (`page`, `size` ≤ 100).
* **Personal columns** (date of birth, gender, personal email, mobile) only with `includePersonal=true` by a holder of
  `student.personal.read`; values are decrypted per row only where the viewer holds that permission at the record's
  unit, otherwise shown as “(not permitted)”. Such views are audited (`report.personal_viewed`).
* **Export**: CSV or XLSX of every matching row (≤ 50,000, otherwise 422 `export_too_large`), streamed through the
  JDK-only `TabularWriter` (formula injection neutralised), `Cache-Control: no-store`, audited (`report.exported`
  with filters as identifiers and the row count — never values).

## 4. Notifications

```
business transaction ──► outbox_event (+ actor_id) ──► RabbitMQ ivorovi.events ──► queue ivorovi.notifications
                                                                                     └► NotificationProducer.handle
```

`NotificationProducer` (at-least-once; unique `(tenant, recipient, source_event_id)` makes redelivery harmless) runs in
the event's organization context and informs:

| Event | Recipients | Category |
|---|---|---|
| `user.activated` after an invitation | the inviter | invitations |
| `user.invitation.delivery_failed` (new event) | the inviter | invitations |
| `user.suspended` / `deactivated` / `reactivated` | active organization-wide holders of `user.manage` (≤ 50) | accounts |
| `role.assigned` / `role.revoked` | the user | access |
| `role.updated` (permission changes) / `role.deactivated` | holders of the role | access |
| `student.status_changed`, `student.enrolment.*` | the student's own sign-in account, if any | students |
| `student.import.validated` (new event), `student.import.finished` (also emitted for failures/cancellation now) | uploader and confirmer | imports |
| `integration.failing`, `webhook.endpoint.disabled` | holders of `integration.manage` | integrations |

The actor of an event is never told about their own action, except for the asynchronous results of work they started
(import validation and completion). Messages contain labels and identifiers only (no free-text reasons, no personal
values). Channel is `IN_APP` with delivery status `DELIVERED` (the only channel; no email/SMS was added). The
notification center (`/notifications`) offers read/unread/all, category filters with unread counts, mark read/unread,
mark all read, deep links and pagination.

## 5. Integration framework

* **Adapter SPI** (`IntegrationAdapter`): `info()` (fields, secret label, kinds, sync support), `validate(config)`,
  `test(config, secret)`, optional `sync(config, secret)`. Built-in, vendor-neutral adapters:
  * `generic-rest` — any HTTPS system (SIS/ERP, LMS, institutional application): health check, connectivity sync
    against a data endpoint (records status and size; **no data is imported or mapped in Phase 1**), authentication
    none/bearer/basic/custom header.
  * `smtp` — email provider check (greeting, EHLO, STARTTLS offered); sends no mail.
  Vendor-specific connectors are not built (no approved scope); they would implement the same SPI.
* **Configuration**: name, kind, adapter, plain fields (declared by the adapter only, ≤ 500 chars each), one secret
  encrypted with `FieldCipher` (`integration.secret`, AAD bound to the organization), write-only in the API
  (`secretSet` flag only). Enable/disable; delete only when disabled.
* **Runs** (`integration_run`): connection tests and syncs, trigger MANUAL/SCHEDULED/RETRY, attempts (default 3 with
  short backoff inside a run), message, duration, retry-of link. Integration keeps connection status
  (UNKNOWN/HEALTHY/FAILING), last check, last success, last sync, last error and consecutive failures; reaching the
  failing threshold (3) emits `integration.failing` once. Scheduled health checks of enabled integrations every 15
  minutes. Failed runs can be retried manually.
* **SSRF guard** (`OutboundGuard`) for every outbound call, re-checked at call time: http(s) only, HTTPS required, no
  credentials or fragments, optional host allow-list (`IVOROVI_INTEGRATIONS_ALLOWED_HOSTS`), no loopback, private,
  carrier-grade NAT, multicast, wildcard or unique-local addresses, link-local/cloud metadata always refused, redirects
  never followed. The development relaxations (`allow-private-networks`, `allow-insecure-http`) make the application
  **fail to start** outside DEVELOPMENT/TEST.

## 6. API clients (scoped machine credentials)

* Key format `ivk_<24-hex key id>_<43-char secret>`; only SHA-256 of the secret is stored with a 4-character hint;
  the key is returned once (create, rotate) and never again.
* `api_key_tenant(key_id)` (SECURITY DEFINER, returns only the organization) resolves the organization before any
  context exists; the secret is then compared in constant time inside that organization's RLS context.
* Restrictions: scopes = READ permissions only, chosen from those the registering administrator holds at the client's
  unit (no escalation), never `integration.*`, `audit.read`, `student.personal.read`; optional unit restriction (grants
  become ORG_UNIT grants); IP allow-list (addresses/CIDR, IPv4/IPv6); expiry; GET/HEAD only (`api_key_read_only`); a key
  together with a bearer token is refused. Suspended organizations' keys stop working.
* Rotation issues a new key and keeps current keys valid for a grace period of 0–168 hours; single keys or the whole
  client can be revoked (reason required, permanent).
* Usage visibility: last use (client and key, at most one write per minute) and daily request/denied counters.
* The platform defines the port (`ApiKeyAuthenticator`, filter before bearer-token authentication); the principal is
  `api-client:<id>` with role `API_CLIENT`; rate limits and audit attribution apply as for users.

## 7. Webhooks

* Endpoints: name, HTTPS URL (SSRF-checked on save and on every attempt), subscribed event types from a catalogue
  (`student.*` lifecycle groups, `student.import.finished`, `staff.*`, `user.*`, `role.assigned/revoked`,
  `org_unit.*`, `academics.*`), identifier opt-in, signing secret (`whsec_…`, encrypted, shown once, rotatable),
  status ACTIVE/PAUSED/AUTO_DISABLED.
* Fan-out (`WebhookFanout`, queue `ivorovi.webhooks`): the event's organization comes from the outbox, endpoints are read
  inside that organization's RLS context → no cross-organization delivery. One delivery per endpoint and event.
* **Payload minimisation**: `{id, type, occurredAt, tenantId, data{resourceType, resourceId, whitelisted fields}}`;
  people-related events carry status/transition/type/role fields only; student, staff and admission numbers only
  with the endpoint's opt-in; names, emails, dates of birth and free-text reasons are never sent.
* Delivery (`WebhookDispatcher`): POST with `Ivorovi-Signature: t=<unix>,v1=<HMAC-SHA256(secret, t + "." + body)>`,
  `Ivorovi-Event-Type`, `Ivorovi-Delivery-Id`; 2xx = delivered; otherwise retried with exponential backoff (30 s base,
  ≤ 1 h) up to 8 attempts, then FAILED; 10 consecutive failures auto-disable the endpoint (audited, managers notified).
  Leases with `FOR UPDATE SKIP LOCKED` allow several instances; bounded pool (4). Paused endpoints keep deliveries
  pending. Delivery history with status, attempts, response code, error, duration and payload; replay creates a new
  delivery linked to the original; a signed `webhook.ping` tests the endpoint.

## 8. Audit

New audited actions: `report.exported`, `report.personal_viewed`, `integration.created/updated/enabled/disabled/
deleted/tested/synced`, `api_client.created/updated/rotated/key_revoked/revoked`, `webhook.created/updated/paused/
resumed/secret_rotated/deleted/tested/replayed/auto_disabled`, `user.invitation.delivery_failed` (now also an event).
Secrets, keys and configuration values are never recorded (configuration keys and key ids only).
