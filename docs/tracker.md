# Development Tracker

Legend: ⬜ not started · 🟨 in progress · ✅ done · ⚠️ done with an open item · ⛔ blocked

| # | Module | Phase | Status | Depends on | API | UI | Tests | Known issues |
|---|---|---|---|---|---|---|---|---|
| 0 | Foundation / platform kernel | 0 | ⚠️ closed pending first green CI run | — | ✅ | ✅ | ✅ 73 BE / 26 FE / 9 E2E | See `phase-0-report.md` §Known issues |
| 1 | Institution & tenant management | 1 | 🟨 read model only | 0 | 🟨 list/get institutions | 🟨 list/detail | 🟨 | No create/update, no campus/department/program, no tenant provisioning |
| 2 | User & access management | 1 | 🟨 resolution + `/me` | 0, 1 | 🟨 | 🟨 My access | 🟨 | No user/role management APIs; principal cache pending |
| 21 | System administration (flags, settings) | 1 / 8 | 🟨 feature flags done | 0 | ✅ flags | ✅ flags | ✅ | Settings, maintenance windows pending |
| 3 | Student & candidate management | 1 (students) / 5 (candidates) | ⬜ planned (Phase 1 inc. 1.6) | 1, 2 | ⬜ | ⬜ | ⬜ | |
| 19 | Audit & compliance | 2 | 🟨 trail + verification | 0 | ✅ | ✅ | ✅ | Consent, data requests, retention pending |
| 17 | Notifications & workflow | 2 / 8 | 🟨 SMTP port only | 0, 2 | ⬜ | ⬜ | ✅ adapter | Templates, preferences, workflows pending |
| 5 | Question bank | 3 | ⬜ | 2 | ⬜ | ⬜ | ⬜ | |
| 6 | Assessment authoring | 3 | ⬜ | 5 | ⬜ | ⬜ | ⬜ | |
| 7 | Assessment scheduling | 3 | ⬜ | 3, 6 | ⬜ | ⬜ | ⬜ | |
| 8 | Online examination engine | 4 | ⬜ | 7 | ⬜ | ⬜ | ⬜ | |
| 10 | Automated & manual evaluation | 4 | ⬜ | 8 | ⬜ | ⬜ | ⬜ | |
| 14 | Results & merit lists | 5 | ⬜ | 10 | ⬜ | ⬜ | ⬜ | |
| 4 | Admissions & eligibility screening | 5 | ⬜ | 3, 14 | ⬜ | ⬜ | ⬜ | |
| 9 | Exam security & proctoring | 6 | ⬜ | 8 | ⬜ | ⬜ | ⬜ | |
| 12 | Coding & technical assessments | 6 | ⬜ (ADR-0004 conditionally approved) | 8, 10 | ⬜ | ⬜ | ⬜ | PoC P1–P8 required on KVM host before implementation |
| 11 | Academic assessment management | 1 (structure) / 7 (grading) | ⬜ planned (Phase 1 inc. 1.2, 1.5) | 14 | ⬜ | ⬜ | ⬜ | |
| 13 | Placement & employability | 7 | ⬜ | 14 | ⬜ | ⬜ | ⬜ | |
| 15 | AI evaluation & recommendations | 7 | ⬜ (flags exist, default OFF) | 10 | ⬜ | ⬜ | ⬜ | |
| 16 | Analytics & reporting | 7 | ⬜ | 10, 14 | ⬜ | ⬜ | ⬜ | |
| 18 | Integrations & APIs | 8 | ⬜ (flags exist) | most | ⬜ | ⬜ | ⬜ | |
| 20 | Subscription & licensing | 8 | ⬜ | 1 | ⬜ | ⬜ | ⬜ | |

## Phase 1 (planned; see `phase-1/plan.md`)

Plan approved with Amendment 1 (`phase-1/plan-amendment-1.md`) and design system (`design/`). Increments 1.0–1.7 **not started**: gated on the first green Phase 0 CI run (D-09).

Open decisions: D-P1-06 (Program/Course/Subject semantics), D-UI-01..03, D-P1-07, D-P1-08.

## Decisions

| ID | Decision | Outcome | Status |
|---|---|---|---|
| D-01 | Identity provider | Keycloak bundled; generic OIDC resource server (ADR-0002) | ✅ Approved |
| D-02 | Backend build | Gradle 9.8 with wrapper; Spring Boot 4.1 / Java 21 | ✅ Approved |
| D-03 | Tenancy model | Shared schema + RLS (ADR-0003) | ✅ Implemented |
| D-04 | Code execution | Own orchestration + Kata (preferred) / gVisor; Judge0 not adopted (ADR-0004) | ✅ Conditionally approved; PoC pending |
| D-05 | Vision worker | Python, separate service | ✅ Approved |
| D-06 | LLM providers | OpenAI-compatible + Anthropic adapters, off by default | ✅ Approved (Phase 7) |
| D-07 | Compliance | India-first (DPDP), GDPR-ready | ✅ Approved |
| D-08 | Lockdown browser | Browser-only first | Proposed |
| D-09 | Source hosting | Private GitHub repository (provided by product owner) | ⏳ **Blocked**: no GitHub account linked to the build session; connect at claude.ai/connect-github and share `owner/repo` |
| D-10 | Infra components | Valkey, SeaweedFS (ADR-0005) | ✅ Implemented |
| D-P1-01 | Org unit types | Fixed four types + configurable labels | ✅ Approved |
| D-P1-02 | Institution SSO | Deferred to Phase 2 | ✅ Approved |
| D-P1-03 | Student logins | On demand only; profiles independent of Keycloak | ✅ Approved |
| D-P1-04 | Keycloak provisioning | Dedicated narrowly scoped service account | ✅ Approved |
| D-P1-05 | `/institutions` endpoints | Remove after consumer verification | ✅ Approved |
