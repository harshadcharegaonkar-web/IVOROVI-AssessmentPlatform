# Engineering Conventions

## Repository layout

```
ivorovi/
├── backend/
│   ├── api/                      # Spring Boot app (modular monolith)
│   │   └── src/main/java/com/ivorovi/
│   │       ├── platform/         # kernel: tenancy, security, audit, outbox, flags, storage, errors
│   │       ├── tenancy/          # one top-level package per module (Spring Modulith module)
│   │       │   ├── api/          #   published module API (interfaces + DTOs other modules may use)
│   │       │   ├── domain/       #   aggregates, value objects, domain events
│   │       │   ├── application/  #   use-case services, transactions, authorization checks
│   │       │   ├── infrastructure/ # JPA repositories, adapters
│   │       │   └── web/          #   REST controllers, request/response DTOs
│   │       └── identity/ …
│   ├── code-runner/
│   └── ai-worker/
├── workers/vision/               # vision worker (language TBD, see D-05)
├── frontend/                     # pnpm workspace
│   ├── apps/web/  apps/exam/
│   └── packages/ui/  packages/api-client/  packages/schemas/
├── deploy/
│   ├── compose/                  # local dev + small on-prem
│   └── helm/ivorovi/
├── docs/  (architecture, adr, modules, api, runbooks)
└── .github/workflows/
```

## Backend

* Java 21, Spring Boot 3.x (latest stable at Phase 0), Gradle Kotlin DSL with version catalog.
* Module boundaries are verified by a Spring Modulith `ApplicationModules.verify()` test. Cross-module access
  goes only through `<module>.api`.
* Controllers are thin. Transactions are on application services. Domain logic sits in aggregates.
* DTOs are Java records. MapStruct handles mapping. Entities never leave the module.
* REST: `/api/v1/...` with plural nouns. Actions as sub-resources (`POST /assessments/{id}/publish`).
  Errors use `application/problem+json`.
* IDs are UUIDv7. Timestamps are `timestamptz`, UTC in API (ISO-8601).
* Flyway: `V{yyyyMMddHHmm}__{module}_{description}.sql`, one migration stream, and every table is owned by one module.
* Never log PII, secrets, answers, or answer keys.

## Frontend

* TypeScript strict, ESLint + Prettier. Feature folders: `src/features/<module>/{routes,components,hooks,api}`.
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
