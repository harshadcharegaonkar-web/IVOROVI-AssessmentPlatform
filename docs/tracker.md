# Development Tracker

Legend: ⬜ not started · 🟨 in progress · ✅ done · ⚠️ done with an open item · ⛔ blocked

| # | Module | Phase | Status | Depends on | API | UI | Tests | Known issues |
|---|---|---|---|---|---|---|---|---|
| 0 | Foundation / platform kernel | 0 | ✅ closed (CI run 41444289316 green) | — | ✅ | ✅ | ✅ 73 BE / 26 FE / 9 E2E | See `phase-0-report.md` §Known issues |
| 1 | Institution & tenant management | 1 | 🟨 organization hierarchy done (1.1) | 0 | ✅ `/org-units` CRUD, move, status, history, tree, summary; tenant settings | ✅ hierarchy, 4 listings, detail, forms, System Settings | ✅ | Programs/courses (1.3), tenant provisioning (super admin), logo upload (needs ADR-0006) pending |
| 2 | User & access management | 1 | 🟨 users, staff, invitations, org and role assignments done (1.2) | 0, 1 | ✅ `/users`, `/staff`, `/invitations`, `/roles`, public invitation endpoints | ✅ users, staff, invitations, access overview, public acceptance page | ✅ | Custom roles and permission configuration (1.3); bulk user import; branded email templates (Phase 2) |
| 21 | System administration (flags, settings) | 1 / 8 | 🟨 feature flags + terminology/time zone settings | 0 | ✅ | ✅ | ✅ | Maintenance windows pending |
| 3 | Student & candidate management | 1 (students) / 5 (candidates) | ⬜ planned (Phase 1 inc. 1.6) | 1, 2 | ⬜ | ⬜ | ⬜ | |
| 19 | Audit & compliance | 2 | 🟨 trail + verification | 0 | ✅ | ✅ | ✅ | Consent, data requests, retention pending |
| 17 | Notifications & workflow | 1 / 2 / 8 | 🟨 in-app notification centre (1.0) + SMTP port | 0, 2 | 🟨 | 🟨 bell + panel | ✅ | Templates, preferences, workflows pending |
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

Plan approved with Amendment 1 and design system.

| Increment | Status | Report |
|---|---|---|
| 1.0 Platform hardening + design-system shell | ✅ complete and signed off (CI run 41452811631); visual baseline `design/evidence/1.0/` | `phase-1/increment-1.0.md` |
| 1.1 Organization hierarchy | ✅ accepted 2026-11-02 subject to six final checks, all passing (`OrgUnitIT.AcceptanceChecks`, CI run 41483588840) | `phase-1/increment-1.1.md` |
| 1.2 User and staff management | ✅ implemented and verified, **awaiting review** (CI run 41570811520 green on `7f2178b`); evidence `design/evidence/1.2/` | `phase-1/increment-1.2.md` |
| 1.3 Custom roles and permission configuration | ⬜ not started; needs authorization | |
| Later | Academic calendar, programs/courses/batches, students (renumbered after 1.3) | `phase-1/plan.md` |

Open decisions: XLSX library (fastexcel) to be requested before the student import increment.

Sequence change (2026-11-02, product owner): users and staff moved to 1.2, custom roles to 1.3; the academic calendar and later increments follow.

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
| D-09 | Source hosting | Private GitHub repository `harshadcharegaonkar-web/IVOROVI-AssessmentPlatform` | ✅ Pushed (no force, history preserved); CI green |
| D-10 | Infra components | Valkey, SeaweedFS (ADR-0005) | ✅ Implemented |
| D-P1-01 | Org unit types | Fixed four types + configurable labels | ✅ Approved |
| D-P1-02 | Institution SSO | Deferred to Phase 2 | ✅ Approved |
| D-P1-03 | Student logins | On demand only; profiles independent of Keycloak | ✅ Approved |
| D-P1-04 | Keycloak provisioning | Dedicated narrowly scoped service account | ✅ Approved |
| D-P1-05 | `/institutions` endpoints | Remove after consumer verification | ✅ Approved |
| D-P1-06 | Academic hierarchy | Program → Course/Branch → Subject/Paper; stable internal types, configurable labels | ✅ Approved |
| D-UI-01..03 | UI | Navy sidebar / navy top bar on mobile; self-hosted Inter; dark mode deferred | ✅ Approved |
| D-P1-07 | Student import | All-or-nothing default + valid-rows-only; DB-wide duplicate detection; idempotent | ✅ Approved |
| D-P1-08 | Document scanning | Validation in dev; quarantine + scanner before production uploads (ADR-0006) | ✅ Conditionally approved |
| D-P1-09 | Libraries | Inter approved; fastexcel/Bucket4j not approved (alternatives used) | ✅ Recorded |
