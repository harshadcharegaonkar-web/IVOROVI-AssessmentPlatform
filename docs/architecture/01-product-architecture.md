# Ivorovi Assessment Platform: Product Architecture

Status: **v0.2** (2026-11-02, updated after Phase 0). Changes to this document require an ADR in `docs/adr/`.

## 1. Repository inspection result

The workspace started empty: no source code, no git history, no existing conventions. This is a greenfield
build. No existing code is overwritten. Toolchain available in the dev container: OpenJDK 21, Gradle 8.14,
Node 22 / npm 10, Docker 29, PostgreSQL 16 client.

## 2. Architectural style

**Modular monolith + a small number of isolated workers.**

| Deployable | Responsibility | Why separate |
|---|---|---|
| `ivorovi-api` (Spring Boot) | All 21 business modules as enforced modules (Spring Modulith) | One transactional boundary, simple ops for on-prem, fast iteration |
| `ivorovi-code-runner` | Sandboxed execution of candidate code | Untrusted code must never run in the API process; separate scaling + network isolation |
| `ivorovi-vision-worker` | Proctoring image/video analysis | GPU/CPU-heavy, optional per deployment, may be Python |
| `ivorovi-ai-worker` | Async LLM jobs (bulk evaluation, recommendations) | Long-running, rate-limited, provider-dependent |
| `web` (React SPA) | Staff, admin, student, recruiter portals | |
| `exam` (React SPA) | Exam-taking client | Minimal bundle, hardened, offline-tolerant, separate CSP |

Modules communicate **in-process through published module APIs and domain events**, never through each
other's tables. Workers communicate via **RabbitMQ** using the transactional **outbox** pattern, so a module can
later be extracted into a service without rewriting its consumers.

```
                   ┌──────────────┐  ┌──────────────┐
  Browser ───────▶ │  web (SPA)   │  │ exam (SPA)   │
                   └──────┬───────┘  └──────┬───────┘
                          │ HTTPS/JSON (OpenAPI)│
                   ┌──────▼───────────────────▼──────┐
  IdP (OIDC/SAML) ◀┤        ivorovi-api              │
                   │  ┌─────────┐ ┌─────────┐ ...    │
                   │  │ module  │ │ module  │        │──▶ PostgreSQL (RLS)
                   │  └─────────┘ └─────────┘        │──▶ Redis
                   │  platform kernel (tenancy, authz,│──▶ S3-compatible storage
                   │  audit, outbox, flags, errors)   │
                   └──────┬──────────────────────────┘
                          │ outbox → RabbitMQ
         ┌────────────────┼──────────────────┐
  ┌──────▼──────┐ ┌───────▼───────┐ ┌────────▼──────┐
  │ code-runner │ │ vision-worker │ │  ai-worker    │──▶ LLM providers (via AI gateway)
  └─────────────┘ └───────────────┘ └───────────────┘
```

## 3. Deployment model (one codebase, three topologies)

| Concern | Multi-tenant SaaS | Dedicated / private cloud | On-premises |
|---|---|---|---|
| Tenancy | Shared DB + schema, `tenant_id` + Postgres RLS | Same code, one or few tenants | Same code, usually one tenant |
| Object storage | AWS S3 / GCS (S3 API) / Azure via S3 gateway | Provider S3 or any S3-compatible store | SeaweedFS (bundled) or customer S3-compatible store |
| Broker | RabbitMQ (managed or self-hosted) | RabbitMQ | RabbitMQ |
| Cache | Valkey / any Redis-protocol service | Same | Valkey (bundled) |
| Identity | Keycloak (bundled) + per-tenant federation | Same | Same, or customer AD/LDAP via Keycloak |
| AI | Hosted LLM providers, per-tenant opt-in | Customer-chosen provider | Local model (OpenAI-compatible endpoint, e.g. vLLM/Ollama) or disabled |
| Packaging | Helm chart | Helm chart | Helm chart or Docker Compose bundle; air-gapped image bundle |
| Secrets | K8s secrets / Vault / cloud secret manager | Same | K8s secrets / Vault / env files |

Rules that make this work:

* Every external dependency sits behind a **port interface** in the platform kernel (`ObjectStorage`,
  `MessagePublisher`, `EmailSender`, `SmsSender`, `LlmProvider`, `VisionAnalyzer`, `CodeExecutor`,
  `SecretResolver`). Adapters are selected by configuration (`ivorovi.storage.provider=s3|filesystem`).
* No cloud-vendor SDK is imported outside an adapter package.
* Configuration follows Spring profiles + environment variables only. No deployment-specific code branches.
* A deployment-level `ivorovi.deployment.mode=SAAS|DEDICATED|ON_PREM` toggles operational features only
  (e.g. self-service tenant signup, billing), never business behavior.

## 4. Multi-tenancy and isolation

**Tenant hierarchy.** `Tenant` (the contracting organization / education group) → **organization units**
(`org_unit`, increment 1.1): `UNIVERSITY` → `COLLEGE` → `CAMPUS` → `DEPARTMENT`, with optional levels
(stand-alone colleges, departments directly under a college or university). Universities and colleges are the
"institutions" (feature overrides, branding). Each unit stores a materialised `ltree` path; parents must be in the
same tenant (composite FK + RLS), codes are unique among siblings, and cycles are rejected. Unit types are fixed;
each tenant may configure display labels (e.g. "School"). Programs and courses follow in increment 1.3.

**Isolation (defense in depth):**

1. **Request layer.** The tenant is resolved from the authenticated token (`tid` claim). It is never taken
   from a request header or body. Platform super admins switch tenant explicitly, and the switch is audited.
2. **Application layer.** `TenantContext` (scoped value) is mandatory. Repositories fail closed if it is absent.
3. **Database layer.** Every tenant-owned table has `tenant_id NOT NULL` plus a **PostgreSQL Row Level Security**
   policy `tenant_id = current_setting('app.tenant_id')::uuid`. The API sets `SET LOCAL app.tenant_id` at
   transaction start. The application connects as a role **without** `BYPASSRLS`. Migrations run as a separate owner role.
4. **Storage layer.** Object keys are prefixed `t/{tenantId}/…`. Downloads use short-lived presigned URLs
   issued only after authorization.
5. **Cache layer.** Redis keys are prefixed `t:{tenantId}:`.
6. **Tests.** A cross-tenant access test suite runs for every module and is a merge gate.

Path to database-per-tenant for very large customers: the `TenantContext` → `DataSource` routing seam exists
from day one, but is not implemented until needed (ADR-0003).

## 5. Identity, authentication, authorization

* **Authentication:** OIDC. Keycloak is the bundled IdP (works in all three topologies, brokers SAML/OIDC/LDAP
  for university SSO, provides MFA). The API is a pure **OAuth2 resource server**, so a customer may swap the IdP.
  Candidates for admission (external users) self-register through a tenant-branded Keycloak realm flow.
* **Authorization lives in Ivorovi, not in the IdP.** Model:
  * `Permission`: fixed catalog defined in code, e.g. `assessment.publish`, `result.approve`, `proctor.review`.
  * `Role`: a named bundle of permissions. The 15 system roles are seeded and immutable. Tenants create custom roles.
  * `RoleAssignment`: (user, role, **scope**) where scope is `TENANT` or `ORG_UNIT` (implemented), later a
    specific resource (e.g. one assessment for an external evaluator).
  * Effective permission = any assignment whose scope contains the target resource's scope. An `ORG_UNIT`
    assignment covers the unit and its whole subtree (ltree prefix check via `Authz.hasInOrgUnit`; unit paths
    cached in Valkey with after-commit eviction).
* **Enforcement:** server-side in every endpoint via `@PreAuthorize("@authz.check('assessment.publish', #id)")`.
  Frontend hiding is cosmetic only. Resource-level checks (e.g. "an evaluator sees only scripts allocated to
  them") live in the module's application service.
* **Sensitive actions** (publishing results, releasing question papers, overriding AI or proctor flags) require
  a re-authentication step (step-up MFA) and are audited.

## 6. Platform kernel (shared, built first)

| Capability | Design |
|---|---|
| Tenancy | `TenantContext`, RLS session setup, tenant-aware base entity |
| AuthZ | Permission catalog, scope resolver, `@authz` bean, method security |
| Errors | RFC 9457 `ProblemDetail` everywhere; stable `type` URIs and error codes; no stack traces to clients |
| Validation | Bean Validation on DTOs + domain invariants in aggregates; Zod mirrors on the frontend |
| Audit | Append-only `audit_event` table, hash-chained per tenant for tamper evidence; who/what/when/where/before/after |
| Events | Spring Modulith events + JDBC outbox → RabbitMQ publisher |
| Feature flags | DB-backed flags with global → tenant → institution overrides; cached in Redis |
| Files | `ObjectStorage` port, virus-scan hook, presigned URLs, content-type allow-list |
| Observability | Micrometer + OpenTelemetry (traces/metrics), JSON logs with `traceId`, `tenantId`, `userId` (no PII) |
| Idempotency | `Idempotency-Key` header support for create/submit endpoints |
| Pagination | Cursor-based for large lists (attempts, events); offset for admin tables; max page size enforced |
| Rate limiting | Redis token bucket per user + per tenant; strict on auth and exam-submit endpoints |
| i18n | Message keys server-side; ICU messages in the frontend |

## 7. Assessment engine (core reuse point)

One engine serves admissions, academics, and placement. The **purpose** (`ADMISSION | ACADEMIC | PLACEMENT |
PRACTICE`) changes workflow and reporting, not the engine.

* **Item types are plugins** (`QuestionTypeHandler` SPI): validate authoring payload, render-model for the
  client, auto-score, and declare whether manual/AI evaluation is needed. Initial set: single choice, multiple
  choice, true/false, numeric, fill-in-blank, matching, ordering, short answer, essay, file upload, coding.
* **Versioning:** a question version is immutable once referenced by a published assessment. Edits create a new version.
* **Blueprint → Paper:** an assessment version defines sections with fixed items or *selection rules*
  (pool + tags + difficulty + count). Each attempt receives a frozen, seeded **paper** so every candidate's
  exact questions and option order are reproducible for re-evaluation and disputes.
* **Answer keys never reach the exam client.** Scoring is server-side only.
* **Attempt state machine:** `SCHEDULED → STARTED → IN_PROGRESS ⇄ PAUSED → SUBMITTED | AUTO_SUBMITTED →
  EVALUATING → EVALUATED → RESULT_PUBLISHED` (+ `INVALIDATED` via audited workflow).
* **Resilience:** the client autosaves every answer change (debounced) with a monotonic sequence number; the
  server stores last-write-wins per item by sequence; local IndexedDB buffering covers network loss; the
  server-authoritative clock decides the deadline.

## 8. Proctoring architecture

* **Signals in the browser:** fullscreen exit, tab/window blur, copy/paste, devtools heuristics, multiple
  displays (where the browser API allows), network drop, periodic webcam snapshots, optional screen capture.
* **Ingest:** events → API (batched) → `proctor_event` table. Snapshots → object storage → message to vision worker.
* **Analysis:** vision worker (behind the `VisionAnalyzer` port) detects no face, multiple faces, face mismatch
  vs. registered photo, phone/object. Each detection is a **flag with a confidence score and evidence**.
* **Humans decide.** Flags feed a proctor review queue. Only a human can mark an incident as confirmed
  misconduct. No automatic disqualification (principle: human approval of consequential AI output).
* **Privacy:** explicit consent screen, retention policy per tenant (default 90 days), biometric data
  encrypted, access to recordings is permission-gated and audited.

## 9. AI architecture

* **AI gateway module** with port `LlmProvider` and adapters: OpenAI-compatible (covers vLLM, Ollama, Azure
  OpenAI, many hosted providers), Anthropic, others as needed. Selection is per tenant and per use case.
* **Prompt templates** are versioned data, not string literals in code. Each AI output records the template version,
  model, provider, input hash, latency, token usage, and cost.
* **PII minimization:** candidate names and identifiers are stripped before prompts. Answers are sent with
  pseudonymous IDs.
* **Guardrails:** structured output (JSON schema) validation, per-tenant token budgets, timeouts, circuit breaker.
* **Human-in-the-loop:** AI evaluation produces *suggested* scores + rationale. An evaluator accepts or edits
  them, and the accepting user is recorded. Recommendations (admission, placement) are advisory and labeled
  as such in the UI.
* **Kill switch:** AI features are feature-flagged per tenant and **off by default**.

## 10. Coding assessments

See **ADR-0004**. We build the orchestration and execution management in a separate `ivorovi-code-runner`
service. Isolation is delegated to established technology behind an `IsolationBackend` SPI: Kata Containers
microVMs where KVM is available, otherwise gVisor. Judge0 was evaluated and is not adopted (GPL-3.0
distribution burden on-prem, privileged-container model, 2024 sandbox-escape CVEs). Hidden test cases stay
server-side. Candidate code never runs in the API process.

## 11. Security architecture (summary)

* OWASP ASVS L2 as the baseline. ASVS L3 controls for exam content and results.
* TLS everywhere. AES-256 at rest (DB volume/storage encryption). Field-level encryption for government IDs,
  biometric references, and contact data where policy requires.
* Question papers for scheduled exams are encrypted at rest with a per-assessment key and released to the
  delivery cache only at the exam window (prevents paper leaks from DB dumps or insiders).
* CSP, HSTS, SameSite cookies, CSRF protection for cookie flows, strict CORS per tenant domain.
* Dependency scanning (OWASP Dependency-Check / npm audit), SAST (Semgrep), container scanning (Trivy),
  DAST (OWASP ZAP baseline) in CI.
* Data protection: designed for India's DPDP Act 2023 and GDPR. Consent records, purpose limitation,
  data export, erasure workflow (respecting statutory academic-record retention).
* Test data: synthetic only (Datafaker-based seeders). Production data must never be loaded into non-prod environments.

## 12. Frontend architecture

* pnpm workspace: `apps/web`, `apps/exam`, `packages/ui` (design tokens + components on Tailwind, built on
  accessible Radix primitives), `packages/api-client` (generated from OpenAPI, TanStack Query hooks),
  `packages/schemas` (Zod).
* Role-specific dashboards are composed from widgets the user's permissions allow. Navigation is generated from
  the permission set.
* Every data view implements loading / empty / error / success states via shared components.
* Accessibility: WCAG 2.2 AA. axe checks run in Playwright tests.

## 13. As built in Phase 0

* Spring Boot 4.1 / Java 21 / Gradle 9.8. Spring Modulith verifies module boundaries (`platform` is the open
  kernel. `tenancy`, `identity`, `features`, `audit` and `seed` are modules).
* Tenant context: `IvoroviJwtAuthenticationConverter` → `TenantContext` (thread-bound, cleared by the outermost
  filter) → `TenantAwareDataSource` sets `app.tenant_id` and `app.platform_context` on **every** connection
  borrow → RLS policies on all tenant tables (a test fails the build if a `tenant_id` table lacks RLS).
* Feature resolution: deployment ceiling → tenant override or catalog default → institution chain (university,
  then college) overrides, each of which may only narrow; campuses and departments inherit. AI and integration flags default OFF. Overrides are cached per tenant in Valkey (JSON) with
  after-commit eviction.
* Events: transactional outbox → `ivorovi.events` topic exchange with publisher confirms. At-least-once delivery,
  `message_id` = outbox id for consumer de-duplication.
* Frontend: pnpm workspace (`packages/ui`, `packages/api-client` generated from `docs/api/openapi.json`,
  `apps/web`). `apps/exam` is deferred to Phase 4.

## 14. Quality and delivery

* CI: build → unit → integration (Testcontainers: Postgres, Redis, RabbitMQ, MinIO) → Spring Modulith
  architecture verification → OpenAPI diff check → frontend lint/type/test → Playwright E2E against Docker
  Compose → security scans → images.
* Performance target (to validate in Phase 8): 10,000 concurrent exam takers per API cluster of modest size,
  autosave p95 < 300 ms, start-exam p95 < 1 s.
