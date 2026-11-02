# Increment 1.0: platform hardening and design-system shell

* Status: **In progress** (started 2026-11-02)
* Depends on: Phase 0 (closed, CI green)

## Functionality

| Area | What it does |
|---|---|
| Tenant-aware caching | Caches the resolved principal (account and grants, TTL 60 s) and the tenant directory entry (TTL 5 min) in Valkey under keys derived from the bound tenant context. Eviction runs after commit. Valkey is shared by all API replicas, so an eviction is visible to every replica immediately |
| Rate limiting | Token buckets in Valkey (atomic Lua script, no new dependency) per user (read 300/min, write 60/min), per tenant (3,000/min) and per IP when unauthenticated (30/min). Returns 429 problem `rate_limited` with `Retry-After` and `RateLimit-Limit/Remaining/Reset` headers. Fails open with a warning metric if Valkey is unavailable |
| Idempotency | `@Idempotent` endpoints require `Idempotency-Key`. Same key and same request → stored response replayed with `Idempotency-Replayed: true`. Same key with a different request → 422. Concurrent duplicate → 409. Records live 24 h, tenant- and user-scoped under RLS |
| Retention | Platform job deletes published outbox rows older than 7 days and expired idempotency records |
| Artifact size | Synthetic-data seeder moves to a separate `backend:devtools` application (Datafaker leaves the production jar). Unused AWS Netty HTTP client excluded |
| Notifications | In-app notification centre backend (list, unread count, mark read). Producers arrive in later increments |
| Design system | Tokens from `docs/design/design-system.md` (navy chrome, AA-adjusted semantic colours, Inter, type and spacing scales). Dark mode removed (D-UI-03) |
| App shell | Navy sidebar with grouped, permission-filtered navigation, collapse to icon rail, active states. White top bar with breadcrumbs, institution selector (only with > 1 option), global search, notification bell and panel, account menu. Navy top bar + drawer on tablet/mobile |
| Restyle | Dashboard, institutions, feature settings, audit log and My Access move to the new components (KPI cards, cards, tables, filter bar) |
| Performance | Route-level code splitting, vendor chunk, `ChunkLoadError` recovery, CI bundle budget (initial JS ≤ 120 kB gzip) |

## Database changes

`V202611021000__platform_idempotency_notifications.sql`:
* `idempotency_record` (RLS: own tenant, or platform context for retention deletes)
* `notification` (RLS: own tenant; API restricts to the recipient)
* Outbox policies split per command; the app role may delete only **published** rows and only in platform context

## APIs

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/notifications?unreadOnly=&page=&size=` | Caller's own notifications |
| GET | `/api/v1/notifications/unread-count` | |
| POST | `/api/v1/notifications/{id}/read` | |
| POST | `/api/v1/notifications/read-all` | |
| — | all endpoints | Rate-limit headers; 429 problem |

## Screens

Shell (sidebar, top bar, drawer, search, notifications, account menu) plus restyled Dashboard, Institutions,
Feature settings, Audit logs and My Access.

## Acceptance criteria

1. Cached steady state: an authenticated request performs no tenant, account or grant query (verified by a statement-count test).
2. Cache keys are tenant-derived. A cross-tenant test shows no leakage between tenants with the same subject.
3. 429 with `Retry-After` at the configured limit. The limiter resets after the window. Unauthenticated requests are limited per IP.
4. Idempotency: replay, 422 on reuse with a different body, 409 on concurrent duplicate, isolation per tenant and user.
5. Retention deletes only published outbox rows and expired idempotency rows.
6. The production `ivorovi-api.jar` contains no Datafaker. The seeder still works via `scripts/seed.sh`. Jar size is reported before and after.
7. The shell matches visual checklist section A at 1440, 1024 and 390 px. The restyled screens pass B1–B14. Axe shows no serious issues.
8. Initial JS ≤ 120 kB gzip, enforced in CI.
9. All Phase 0 suites still pass (backend, frontend, smoke, E2E). CI is green.
