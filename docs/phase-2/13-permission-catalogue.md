# 13 — Updated permission catalogue (proposal)

Rules carried forward: permissions are catalogue codes; UNIT-scoped permissions inherit down the hierarchy; custom roles
are bounded by the assigner's ceiling; PLANNED permissions become AVAILABLE only when their feature ships. The current
catalogue placeholders (`assessment.*`, `exam.*`, `evaluation.*`, `result.*`, `placement.*`, `ai.*`) are **refined** into
the codes below (decision P2-07: replace the coarse placeholders before they were ever grantable, so no data migration).

## New and refined permissions

| Domain | Code | Level | Scope | Purpose |
|---|---|---|---|---|
| Candidates | `candidate.read` / `candidate.personal.read` / `candidate.manage` | R / R / M | UNIT | applicant records; personal data separately |
| Admissions | `admission.cycle.manage` | M | UNIT | cycles, forms, rules, stages |
| | `admission.application.read` / `.review` | R / W | UNIT | view / verify and move applications |
| | `admission.merit.manage` / `admission.merit.publish` | M / M | UNIT | generate / publish merit lists (four-eyes) |
| | `admission.offer.manage` | M | UNIT | offers and conversion to students |
| Item bank | `question.read` / `question.author` / `question.review` | R / W / M | UNIT | bank access, drafting, approval |
| | `question.secure.read` | R | UNIT | secure-bank custodians only |
| Assessments | `assessment.read` / `assessment.author` / `assessment.publish` | R / W / M | UNIT | blueprints and publication |
| Examinations | `exam.schedule` | M | UNIT | events, sessions, allocations, admit cards |
| | `exam.conduct` | W | UNIT | start/pause/end sessions, extend time |
| | `exam.proctor` | W | UNIT | admit candidates, verify identity, record incidents, review flags |
| | `exam.incident.decide` | M | UNIT | decisions on incidents (disqualification four-eyes) |
| Evaluation | `evaluation.mark` | W | UNIT | mark allocated scripts |
| | `evaluation.moderate` | M | UNIT | moderate, resolve discrepancies |
| | `evaluation.manage` | M | UNIT | plans, allocation, locking marks |
| Results | `result.read` / `result.approve` / `result.publish` | R / M / M | UNIT | view, approve, publish (four-eyes) |
| | `result.personal.read` | R | UNIT | per-candidate result details beyond own unit summaries |
| Placement | `placement.read` / `placement.manage` | R / M | UNIT | drives, shortlists |
| | `placement.recruiter.read` | R | ORGANIZATION (drive-scoped by data) | external recruiters: shortlisted, consenting candidates of their drives only |
| AI | `ai.configure` | M | ORGANIZATION | providers, use-case policies, thresholds |
| | `ai.use` | W | UNIT | request AI drafts/suggestions |
| | `ai.audit.read` | R | ORGANIZATION | AI invocation and oversight reports |
| Service identities (KI-59) | under `integration.manage` | — | — | no new code |
| Platform | `platform.ai_provider.manage`, `platform.license.manage` | M | PLATFORM | super admin only |

Never grantable to API clients or service identities: all `*.publish`, `*.decide`, `*.approve`, `*.personal.read`,
`question.secure.read`, `ai.configure`, `exam.conduct`, `exam.proctor`.

## Role matrix (system roles; scope = where the role is assigned)

| Permission group | Super admin | Org admin | Univ/college admin (INSTITUTION_ADMIN) | Admissions officer | Exam controller | Faculty | Assessment designer | Evaluator | Moderator (new role) | Proctor (EXAM_PROCTOR) | Placement officer | Student/candidate | External recruiter |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| candidate.* | — | all | all | all | read | — | — | — | — | read (own session) | — | own record (self-service, not a permission) | — |
| admission.* | — | all | all | all | — | — | — | — | — | — | — | own application | — |
| question.* | — | read | read, review | — | read | read, author | read, author, review | — | — | — | read | — | — |
| question.secure.read | — | — | — | — | named custodians only (explicit grant) | — | named | — | — | — | — | — | — |
| assessment.* | — | read | read, publish | read | read, publish | read, author | read, author | — | — | — | read | — | — |
| exam.schedule / conduct | — | ✓ | ✓ | schedule (admission events) | ✓ | — | — | — | — | — | schedule (drive events) | — | — |
| exam.proctor | — | — | — | — | ✓ | — | — | — | — | ✓ | — | — | — |
| exam.incident.decide | — | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — |
| evaluation.mark | — | — | — | — | — | ✓ | — | ✓ | ✓ | — | — | — | — |
| evaluation.moderate | — | — | — | — | ✓ | — | — | — | ✓ | — | — | — | — |
| evaluation.manage | — | ✓ | ✓ | — | ✓ | — | — | — | — | — | — | — | — |
| result.read / approve / publish | — | ✓ / ✓ / ✓ | ✓ / ✓ / ✓ | read (admission) | ✓ / ✓ / ✓ | read (own offerings) | — | — | read | — | read (drives) | own published results | — |
| placement.* | — | ✓ | ✓ | — | — | — | — | — | — | — | ✓ | own applications | recruiter.read |
| ai.use / configure | platform providers | configure + use | use | use (analytics) | use | use | use | use (suggestions) | use | — | use | — | — |

Notes: “University administrator” and “college administrator” are the existing INSTITUTION_ADMIN role assigned at a
university or college unit (scope inheritance gives university admins every college below). The platform super
administrator never holds tenant content permissions; access to a tenant's data for support uses time-boxed,
tenant-approved support grants (decision P2-08), audited. Candidates and recruiters act on their own data through
self-service endpoints, not through staff permissions.
