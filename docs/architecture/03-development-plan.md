# Development Plan and Implementation Sequence

## Guiding rules

* Build **vertical slices**: each module ships with migration, API, UI, authorization, tests, and docs together.
* A module may start only when its dependencies meet the Definition of Done (see `docs/conventions.md`).
* The first business milestone is a real end-to-end workflow, not breadth: **"An institution runs an
  admission entrance test and publishes a merit list."** Everything before Phase 5 serves that goal.

## Dependency graph (critical path in bold)

```
Foundation ─▶ **Tenancy** ─▶ **Identity & Access** ─▶ **People** ─▶ **Item bank** ─▶ **Authoring** ─▶ **Scheduling**
                   │                │                                                              │
                   ▼                ▼                                                              ▼
               Sysadmin/Flags   Audit, Notifications(basic)                                  **Delivery (exam engine)**
                                                                                                    │
                                     ┌──────────────────────────────┬───────────────────────────────┤
                                     ▼                              ▼                               ▼
                               **Evaluation** ─▶ **Results/Merit** ─▶ Admissions      Proctoring    Coding
                                     │                    │
                                     ▼                    ▼
                                  AI evaluation      Academics, Placement ─▶ Analytics ─▶ Integrations ─▶ Licensing
```

## Phases

| Phase | Scope | Exit criteria (demonstrable) |
|---|---|---|
| **0. Foundation** | Repo layout, Gradle + pnpm builds, Docker Compose (Postgres, Redis, RabbitMQ, MinIO, Keycloak, Mailpit), platform kernel (tenancy context + RLS, ProblemDetail errors, audit writer, outbox, feature flags, OpenAPI, observability), CI pipeline, UI design system + app shell, synthetic data seeder | `docker compose up` gives a running stack. Login through Keycloak works. A sample protected endpoint passes RLS and cross-tenant tests. CI is green. |
| **1. Tenancy + Identity & Access** (M1, M2, M21 partial) | Tenant/institution/campus/department/program CRUD, scope tree, users, invitations, system + custom roles, scoped role assignments, permission checks, admin screens | A super admin creates a tenant. The org admin builds the hierarchy, invites users, and assigns scoped roles. Forbidden actions return 403 server-side. Audit entries exist. |
| **2. People + Audit + Notifications (basic)** (M3, M19, M17 partial) | Student and candidate records, bulk CSV import with validation report, cohorts, audit log viewer, email notifications via templates | Bulk import of 5k synthetic students with an error report. The audit trail is searchable. An invitation email arrives in Mailpit. |
| **3. Item bank + Authoring + Scheduling** (M5, M6, M7) | Question types (objective set first, then essay/upload), versioning, review workflow, import (CSV/QTI later), assessment blueprint with sections and random rules, publish validation, exam events/windows/allocations/accommodations | A designer authors 50 questions, gets them reviewed, builds an assessment with random selection, publishes it, and schedules it for a cohort. |
| **4. Delivery + Evaluation** (M8, M10) | Exam client (`apps/exam`), paper generation, autosave, timer, resume after disconnect, submit/auto-submit; auto-scoring; manual marking queues with rubrics, double marking, moderation | 200 synthetic candidates take the test concurrently (scripted). Objective items auto-score. Essays are marked by two evaluators and moderated. |
| **5. Results + Admissions** (M14, M4) | Result computation, review/approval/publication, merit lists with tie-break + categories; admission cycles, application forms, eligibility rules, stage pipeline, offer generation | **Milestone 1:** an admission cycle runs end to end, from application through eligibility, entrance test, merit list, and offer. |
| **6. Proctoring + Coding** (M9, M12) | Client signals, snapshots, vision worker (pluggable), review queue and incidents; code-runner sandbox, coding item type, hidden tests | A proctored exam produces flags. The proctor reviews and decides. A coding question is executed safely and scored. |
| **7. Academics + Placement + AI + Analytics** (M11, M13, M15, M16) | Courses, internal/semester components, weightage, grade computation; companies, drives, recruiter portal, shortlists; AI gateway, AI-suggested essay scoring, recommendations; item analysis (difficulty/discrimination), cohort dashboards | **Milestone 2:** a semester internal assessment computes grades, a placement drive shortlists candidates for a recruiter, and AI suggestions are accepted or edited by evaluators. |
| **8. Integrations + Licensing + Hardening** (M18, M20, M17 workflows, M21) | Public API with API clients and scopes, webhooks, SIS/LMS connectors (LTI 1.3), configurable workflows, plans/entitlements, signed on-prem licenses, Helm chart, air-gapped bundle, load testing, ZAP/pen-test fixes, DR runbooks | **GA readiness:** load target met, no open critical or high security findings, and the deployment is verified on SaaS, dedicated, and on-prem topologies. |

## Per-module workflow (applied to every module)

1. Requirements + user stories + acceptance criteria → `docs/modules/<module>.md`
2. Tables and relationships → Flyway migration + domain model update
3. Domain + application services + REST controllers (OpenAPI annotated)
4. Authorization (permission catalog entries, `@PreAuthorize`, resource checks) + server validation
5. Frontend screens with all UI states
6. Events/integration with kernel services (audit, notifications, outbox)
7. Tests: unit, Testcontainers integration, cross-tenant, authorization matrix, Playwright E2E
8. Docs: module doc, API reference (generated), tracker update
9. Demo script: a reproducible walkthrough with seeded synthetic data

## Next action

Start **Phase 0 (Foundation)** once the pending decisions in `docs/tracker.md` are confirmed or accepted as
recommended.
