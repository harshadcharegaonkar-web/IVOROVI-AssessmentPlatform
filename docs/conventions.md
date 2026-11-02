# Engineering Conventions

## Repository layout

```
ivorovi/
├── backend/
│   ├── api/                      # Spring Boot app (modular monolith)
│   │   └── src/main/java/com/ivorovi/
│   │       ├── platform/         # kernel: tenancy, security, audit, outbox, flags, storage, errors
│   │       ├── tenancy/          # one top-level package per module (Spring Modulith module)
│   │       │   ├── *.java        #   base package = published module API (interfaces, records, services)
│   │       │   ├── internal/     #   entities, repositories, implementation (not accessible to other modules)
│   │       │   └── web/          #   REST controllers and request/response records
│   │       └── identity/ features/ audit/ seed/ …
│   ├── code-runner/              (Phase 6, ADR-0004)
│   └── ai-worker/                (Phase 7)
├── workers/vision/               # Python vision worker (Phase 6)
├── frontend/                     # pnpm workspace
│   ├── apps/web/                 # apps/exam/ arrives in Phase 4
│   └── packages/ui/  packages/api-client/
├── deploy/
│   ├── compose/                  # local dev + small on-prem
│   └── helm/ivorovi/             # Phase 8
├── docs/  (architecture, adr, modules, api, runbooks)
└── .github/workflows/
```

## Backend

* Java 21, Spring Boot 4.1, Gradle 9.8 (Kotlin DSL, version catalog `gradle/libs.versions.toml`).
* Module boundaries are verified by a Spring Modulith `ApplicationModules.verify()` test. Other modules may use
  only a module's base package. `platform` is an open kernel module that must never depend on business modules.
* Controllers are thin. Transactions are on application services. Domain logic sits in aggregates.
* DTOs are Java records with explicit static mapping methods. Entities never leave the module.
* REST: `/api/v1/...` with plural nouns. Actions as sub-resources (`POST /assessments/{id}/publish`).
  Errors use `application/problem+json`.
* IDs are UUIDv7. Timestamps are `timestamptz`, UTC in API (ISO-8601).
* Flyway: `V{yyyyMMddHHmm}__{module}_{description}.sql`, one migration stream, and every table is owned by one module.
* Never log PII, secrets, answers, or answer keys.

## Frontend

* TypeScript 6 strict, ESLint (incl. React Compiler hook rules) + Prettier. Screens live in `apps/web/src/routes/<area>/`; shared UI goes to `packages/ui`; API types come only from the generated client.
* Server state only via TanStack Query hooks from `packages/api-client`. Forms use React Hook Form + Zod.
* No raw colors or spacing. Use design tokens only. Components must be keyboard accessible.

## Git and CI

* Trunk-based. Short-lived branches `feat/<module>-<topic>`. Conventional Commits.
* PR merge gates: build, tests, Modulith verify, cross-tenant suite, OpenAPI diff reviewed, security scans pass.

## Definition of Done (per module)

Workflow works end to end · data persists · permissions enforced server-side (authorization matrix tests) ·
validation and error handling · OpenAPI documented · automated tests pass (unit, integration,
cross-tenant, E2E) · no critical or high security issues · loading/empty/error/success UI states · module doc
and tracker updated · demo script runs on seeded synthetic data.
