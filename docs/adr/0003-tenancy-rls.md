# ADR-0003: Shared-schema multi-tenancy with PostgreSQL Row Level Security

* Status: Proposed
* Date: 2026-11-02

## Context
SaaS needs efficient onboarding of many small colleges. Large universities and on-prem customers may need stronger
physical isolation. Exam data is highly sensitive, so a single missed `WHERE tenant_id = ?` must not leak data.

## Decision
* Every tenant-owned table has `tenant_id` and an RLS policy bound to `current_setting('app.tenant_id')`.
* The API's DB role has no `BYPASSRLS`. The tenant is set with `SET LOCAL` per transaction from the authenticated token.
* A `TenantDataSourceRouter` seam exists but routes to a single pool until DB-per-tenant is required.

## Consequences
* Defense in depth with modest operational cost. RLS adds a small query overhead, so indexes lead with `tenant_id`.
* Background jobs must explicitly set a tenant context, and cross-tenant platform jobs use a separate audited role.
