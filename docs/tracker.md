# Development Tracker

Legend: ⬜ not started · 🟨 in progress · ✅ done · ⛔ blocked

| # | Module | Phase | Status | Depends on | API | UI | Tests | Known issues |
|---|---|---|---|---|---|---|---|---|
| 0 | Foundation / platform kernel | 0 | 🟨 architecture docs done | — | ⬜ | ⬜ | ⬜ | — |
| 1 | Institution & tenant management | 1 | ⬜ | 0 | ⬜ | ⬜ | ⬜ | |
| 2 | User & access management | 1 | ⬜ | 0, 1 | ⬜ | ⬜ | ⬜ | |
| 21 | System administration (flags, settings) | 1 / 8 | ⬜ | 0 | ⬜ | ⬜ | ⬜ | |
| 3 | Student & candidate management | 2 | ⬜ | 1, 2 | ⬜ | ⬜ | ⬜ | |
| 19 | Audit & compliance | 2 | ⬜ | 0 | ⬜ | ⬜ | ⬜ | |
| 17 | Notifications & workflow | 2 / 8 | ⬜ | 0, 2 | ⬜ | ⬜ | ⬜ | |
| 5 | Question bank | 3 | ⬜ | 2 | ⬜ | ⬜ | ⬜ | |
| 6 | Assessment authoring | 3 | ⬜ | 5 | ⬜ | ⬜ | ⬜ | |
| 7 | Assessment scheduling | 3 | ⬜ | 3, 6 | ⬜ | ⬜ | ⬜ | |
| 8 | Online examination engine | 4 | ⬜ | 7 | ⬜ | ⬜ | ⬜ | |
| 10 | Automated & manual evaluation | 4 | ⬜ | 8 | ⬜ | ⬜ | ⬜ | |
| 14 | Results & merit lists | 5 | ⬜ | 10 | ⬜ | ⬜ | ⬜ | |
| 4 | Admissions & eligibility screening | 5 | ⬜ | 3, 14 | ⬜ | ⬜ | ⬜ | |
| 9 | Exam security & proctoring | 6 | ⬜ | 8 | ⬜ | ⬜ | ⬜ | |
| 12 | Coding & technical assessments | 6 | ⬜ | 8, 10 | ⬜ | ⬜ | ⬜ | |
| 11 | Academic assessment management | 7 | ⬜ | 14 | ⬜ | ⬜ | ⬜ | |
| 13 | Placement & employability | 7 | ⬜ | 14 | ⬜ | ⬜ | ⬜ | |
| 15 | AI evaluation & recommendations | 7 | ⬜ | 10 | ⬜ | ⬜ | ⬜ | |
| 16 | Analytics & reporting | 7 | ⬜ | 10, 14 | ⬜ | ⬜ | ⬜ | |
| 18 | Integrations & APIs | 8 | ⬜ | most | ⬜ | ⬜ | ⬜ | |
| 20 | Subscription & licensing | 8 | ⬜ | 1 | ⬜ | ⬜ | ⬜ | |

## Pending decisions

| ID | Decision | Recommendation | Status |
|---|---|---|---|
| D-01 | Identity provider | Bundle **Keycloak**. API stays a generic OIDC resource server. | Awaiting confirmation |
| D-02 | Backend build tool | **Gradle** (Kotlin DSL). | Awaiting confirmation |
| D-03 | Tenancy model | Shared schema + `tenant_id` + Postgres RLS, with a DB-per-tenant seam (ADR-0003). | Proposed |
| D-04 | Code execution engine | Own sandbox runner (gVisor/nsjail) behind `CodeExecutor`. Judge0 is an alternative, but its GPL-3.0 licence needs legal review. | Awaiting confirmation |
| D-05 | Vision worker language | **Python** (best CV ecosystem), isolated behind a message contract. | Awaiting confirmation |
| D-06 | Initial LLM providers | OpenAI-compatible adapter + Anthropic adapter. Off by default per tenant. | Awaiting confirmation |
| D-07 | Primary market / compliance | India (DPDP Act 2023, UGC/AICTE norms) + GDPR-ready. Affects data residency and retention defaults. | **Needs product input** |
| D-08 | Lockdown browser | Phase 6 is browser-only (no installed client). A desktop lockdown client is a later option. | Proposed |
| D-09 | Source hosting | The workspace has no git remote. Code is committed locally only until a GitHub repo is attached. | **Needs user action** |
