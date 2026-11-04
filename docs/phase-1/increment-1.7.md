# Increment 1.7 — Dashboard and integration improvements

**Status: delivered for review (2026-11-04).** Authorized 2026-11-03 together with the acceptance of increment 1.6.
Not a production-readiness claim: the release blockers in `runbooks/production-readiness.md` remain open.

## Part A — 1.6 acceptance items (done before 1.7 work)

| Item | Result |
|---|---|
| KI-53 refused uploads audited | Fixed: authenticated refusals in the hash-chained audit trail in their own transaction (actor, tenant, institution, time, reason, outcome, correlation ID); unauthenticated refusals in the platform `security_event` log; no contents or personal values recorded (`UploadRefusalAuditIT`, 2 tests) |
| Scanner clarification | `runbooks/production-readiness.md` §“What scans files today”: tests use a **simulated** scanner (`FakeMalwareScanner`); `ClamAvScanner` has not run against a real clamd; dev/demo have no scanner. Real scanner + staging verification remains release blocker RB-1 for documents **and** import files |
| Outstanding | `IVOROVI_DATA_KEY` backup/recovery/rotation (RB-2, RB-3), KI-46 (RB-4), KI-48 (RB-5) retained as open blockers |

## Delivered scope (Part B)

| # | Requirement | Delivered |
|---|---|---|
| 1 | Executive dashboard | `/` from `GET /insights/executive`: universities, colleges, campuses, departments, programs, courses, subjects, active academic years, active batches, students, active students, admission records, new admissions, faculty and staff, active users, pending invitations, import activity — each section permission-scoped, omitted without permission |
| 2 | Academic dashboard | `/dashboards/academic`: programs by level/institution, courses per program, subjects by category, batches by status/year, enrolment by academic year and semester, faculty assignment overview and uncovered offerings by department |
| 3 | Student dashboard | `/students` extended: 12-month admissions, initial enrolments and progressions; enrolment changes by type; semester distribution; existing KPIs and distributions kept |
| 4 | Staff dashboard | `/dashboards/staff`: totals by status, linked accounts, by category/department/campus/employment type, faculty assignment summary; no workload metric (not recorded) |
| 5 | Import operations | `/dashboards/imports`: outcomes, in progress, awaiting action, records processed/created/updated/rejected, median/average/p95 duration, throughput, jobs per month, recent jobs with links, date range |
| 6 | Reporting | `/reports`, `/reports/:key`: students, enrolments, staff, batches, imports; date range, unit, academic year, program, course, batch and status filters (URL), server-side paging and sorting, CSV/XLSX export (≤ 50,000 rows); personal columns only on request with permission (audited); every export audited |
| 7 | Notifications | Event-driven producer for invitations (accepted, email not delivered), account status changes, role grants/revocations/permission changes, student status and enrolment changes (student's own account), import validation and completion/failure, integration and webhook failures; notification center with read/unread, category filters, history, deep links, delivery status (in-app only) |
| 8 | Integration framework | Integrations with vendor-neutral adapters (REST/HTTPS, SMTP), validation, enable/disable, connection status, tests, syncs (connectivity), run history, retries (in-run and manual), last success/sync, scheduled health checks, failure tracking and alerts |
| 9 | API management | API clients with read-only scoped keys (shown once, hashed), unit restriction, IP allow-list, expiry, rotation with grace, key/client revocation, usage (daily, last use), audit |
| 10 | Webhooks | Tenant-scoped endpoints, event catalogue, HMAC-SHA256 signing, retries with backoff, delivery status and failure tracking, auto-disable, replay, test ping, pause/resume, secret rotation, minimal payloads (identifiers opt-in), audit |
| 11 | Reusable adapters | `IntegrationAdapter` SPI; webhook and API-key layers independent of any vendor |
| 12 | UI | 13 new screens (+ extended student dashboard and executive dashboard), navigation group “Insights”, Configuration entries for Integrations, API Clients, Webhooks; Notifications in the footer and bell “View all” |

Not built (by instruction): vendor-specific connectors, new notification channels, institution-specific SSO.

## Acceptance criteria

| # | Criterion (abridged) | Evidence |
|---|---|---|
| 1 | Dashboards show real data only | All figures from live queries (`DashboardService`); Meridian figures match the demo specification (`demo_data.py verify`) |
| 2 | Permission-scoped | `InsightsIT` (5 vs 3 students by scope; subtree outside scope = 0; sections omitted) |
| 3 | No cross-tenant data | `InsightsIT.apiResponsesExposeNoOtherOrganizationsData`, `ApiClientIT.keysNeverReachAnotherOrganization`, `WebhooksIT…EventsNeverCrossOrganizations`, `NotificationsIT.eventsNeverCrossOrganizations`, `IntegrationsIT` (404 across organizations) |
| 4 | Reports filter, paginate, export | `InsightsIT.reportsAreScoped…`, `exportsAreScopedAndAudited`; E2E “reports: filter, page and export CSV” |
| 5 | Sensitive data only when authorized | `personalColumnsNeedPermissionAndAreAudited`; webhook payload whitelist tests |
| 6 | Exports audited | `report.exported` audit with filters and row count |
| 7 | Notifications for listed events | `NotificationsIT` (5 tests) + live demo (RabbitMQ consumer produced import notifications) |
| 8 | Read/unread, history, deep links | `NotificationsIT.readUnreadHistoryAndCategories`; E2E notification center |
| 9 | Integration config, status, history, retries | `IntegrationsIT` (4 tests), E2E REST integration test |
| 10 | Failures do not affect the core | Timeouts, no transactions around outbound calls, bounded pools; failing runs recorded (`IntegrationsIT`), webhook auto-disable (`WebhooksIT`) |
| 11 | API credentials scoped, rotatable, revocable, never shown again | `ApiClientIT` (5 tests), E2E API client test |
| 12 | Webhooks signed, retried, replayable | `WebhooksIT` (5 tests), benchmark (1,000/1,000 valid signatures) |
| 13 | SSRF protection | `OutboundGuardTest` (3), smoke check for the metadata address |
| 14 | Audit of administrative actions | Audit actions listed in `architecture/09-insights-and-integrations.md` §8 |
| 15 | Accessibility | axe (WCAG 2.1 AA, serious/critical = 0) on every new screen in E2E; chart table views |
| 16 | Responsive | E2E desktop/tablet/mobile, no horizontal page scroll; screenshots at 1440, 1024, 390 px |
| 17 | Performance | `performance/insights-integrations-benchmark.md` (36,400 students) |
| 18 | Bundle budget | Initial 157.4 kB gzip ≤ 180 kB; largest lazy chunk 35.8 kB ≤ 40 kB |
| 19 | Tests green | see below |
| 20 | Documentation | this file; architecture 09; API examples; permission catalogue; security assessment; known issues KI-59…KI-64; tracker; visual checklist |

## Verification results (local, 2026-11-04)

* Backend: 65 test classes, **242 tests, 0 failures, 0 errors** on the final code, including the new suites
  `InsightsIT` 7, `NotificationsIT` 5, `IntegrationsIT` 4, `ApiClientIT` 5, `WebhooksIT` 5, `OutboundGuardTest` 3 and
  `UploadRefusalAuditIT` 2. **CI run 41763456942 green** on all four jobs (security scan, frontend, backend, end-to-end with the compose stack and real Keycloak) for commit `4f7163f`.
* Frontend: typecheck, lint, 39 unit tests green; production build within the bundle budget.
* E2E (Playwright, real Keycloak): full suite **100 passed** (including 14 new 1.7 tests on desktop, tablet, mobile).
* Smoke test: **94 passed, 0 failed** (18 new 1.7 checks, including API-key read-only and SSRF refusal).
* Benchmark: `performance/insights-integrations-benchmark.md`.
* Screenshots: `design/evidence/1.7/` (24 images, Meridian demonstration organization).

## Demonstration

`scripts/demo/demo_data.py rebuild` now also creates two working integrations (Mailpit SMTP relay, REST check of the
API's own health endpoint; both tested and enabled; one sync) and a read-only API client (key shown once and discarded).
Sign in as `demo.admin@meridian.test` and open Dashboard, Insights, Reports, Notifications and Configuration →
Integrations / API Clients / Webhooks.

## Known limitations

KI-59 (API client scopes checked at registration), KI-60 (REST sync is connectivity only), KI-61 (health checks per
instance), KI-62 (at-least-once webhooks), KI-63 (in-app notifications only), KI-64 (outbox lag inherited).
