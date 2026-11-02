# Development Tracker

Legend: ⬜ not started · 🟨 in progress · ✅ done · ⚠️ done with an open item · ⛔ blocked

| # | Module | Phase | Status | Depends on | API | UI | Tests | Known issues |
|---|---|---|---|---|---|---|---|---|
| 0 | Foundation / platform kernel | 0 | ⚠️ (CI run pending) | — | ✅ | ✅ | ✅ 73 BE / 26 FE / 9 E2E | See `phase-0-report.md` §Known issues |
| 1 | Institution & tenant management | 1 | 🟨 read model only | 0 | 🟨 list/get institutions | 🟨 list/detail | 🟨 | No create/update, no campus/department/program, no tenant provisioning |
| 2 | User & access management | 1 | 🟨 resolution + `/me` | 0, 1 | 🟨 | 🟨 My access | 🟨 | No user/role management APIs; principal cache pending |
| 21 | System administration (flags, settings) | 1 / 8 | 🟨 feature flags done | 0 | ✅ flags | ✅ flags | ✅ | Settings, maintenance windows pending |
| 3 | Student & candidate management | 2 | ⬜ | 1, 2 | ⬜ | ⬜ | ⬜ | |
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
| 12 | Coding & technical assessments | 6 | ⬜ (ADR-0004 proposed) | 8, 10 | ⬜ | ⬜ | ⬜ | Spike: Kata vs gVisor |
| 11 | Academic assessment management | 7 | ⬜ | 14 | ⬜ | ⬜ | ⬜ | |
| 13 | Placement & employability | 7 | ⬜ | 14 | ⬜ | ⬜ | ⬜ | |
| 15 | AI evaluation & recommendations | 7 | ⬜ (flags exist, default OFF) | 10 | ⬜ | ⬜ | ⬜ | |
| 16 | Analytics & reporting | 7 | ⬜ | 10, 14 | ⬜ | ⬜ | ⬜ | |
| 18 | Integrations & APIs | 8 | ⬜ (flags exist) | most | ⬜ | ⬜ | ⬜ | |
| 20 | Subscription & licensing | 8 | ⬜ | 1 | ⬜ | ⬜ | ⬜ | |

## Decisions

| ID | Decision | Outcome | Status |
|---|---|---|---|
| D-01 | Identity provider | Keycloak bundled; generic OIDC resource server (ADR-0002) | ✅ Approved |
| D-02 | Backend build | Gradle 9.8 with wrapper; Spring Boot 4.1 / Java 21 | ✅ Approved |
| D-03 | Tenancy model | Shared schema + RLS (ADR-0003) | ✅ Implemented |
| D-04 | Code execution | Own orchestration + Kata/gVisor isolation; Judge0 not adopted (ADR-0004) | ⏳ **Awaiting approval** |
| D-05 | Vision worker | Python, separate service | ✅ Approved |
| D-06 | LLM providers | OpenAI-compatible + Anthropic adapters, off by default | ✅ Approved (Phase 7) |
| D-07 | Compliance | India-first (DPDP), GDPR-ready | ✅ Approved |
| D-08 | Lockdown browser | Browser-only first | Proposed |
| D-09 | Source hosting | No git remote in this workspace | ⏳ **Needs user action**: attach a GitHub repository |
| D-10 | Infra components | Valkey, SeaweedFS (ADR-0005) | ✅ Implemented |
