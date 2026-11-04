# 14 — API design

Conventions from Phase 1 apply: REST under `/api/v1`, OpenAPI as the contract (`docs/api/openapi.json`, frontend client
generated), Problem Details errors with stable codes, offset paging (`PageResponse`), optimistic `version` checks,
`Idempotency-Key` on creates, permission checks server-side, audit on consequential actions, minimised webhook payloads.

## Surfaces

| Surface | Base path | Callers | Auth |
|---|---|---|---|
| Staff API | `/api/v1/...` | apps/web, API clients (read), service identities | OIDC bearer; API keys (read-only, KI-59 model) |
| Candidate self-service | `/api/v1/me/...` | candidates/students | OIDC bearer (candidate realm client) |
| Public applications | `/api/v1/public/admissions/...` | prospective candidates | none + rate limit + verification codes (as 1.2 invitations) |
| Delivery | `/api/v1/delivery/...` | apps/exam | attempt-scoped token issued at admission to the session (short-lived, bound to attempt and device key) |
| Recruiter | `/api/v1/recruiter/...` | external recruiters | OIDC (recruiter accounts) with drive-scoped data filter |
| LTI | `/lti/...` | LMS platforms | LTI 1.3 (OIDC launch, signed JWT, platform registration per tenant) |

## Representative endpoints

```
POST /api/v1/item-banks/{bankId}/items                 create draft item (type-specific payload)
POST /api/v1/items/{id}/versions/{v}/review            approve/reject (question.review)
POST /api/v1/assessments/{id}/versions                 freeze a version (validation report on failure: 422)
POST /api/v1/assessments/{id}/versions/{v}/publish     (assessment.publish; four-eyes token if required)
POST /api/v1/exam-events                               schedule (purpose, version, sessions)
POST /api/v1/exam-events/{id}/allocations:bulk         allocate candidates/students (async job, report)
POST /api/v1/sessions/{id}/start | /pause | /end       (exam.conduct)

POST /api/v1/delivery/sessions/{id}/admission          candidate enters: access code, identity photo → attempt token
GET  /api/v1/delivery/attempts/{id}                    sealed paper (no keys), deadline, serverNow, latest responses
POST /api/v1/delivery/attempts/{id}/responses          batch of {itemId, clientSeq, payload}; returns ackSeq
POST /api/v1/delivery/attempts/{id}/heartbeat          lastSeen, signals batch
POST /api/v1/delivery/attempts/{id}/submit             Idempotency-Key; returns receipt {sealHash, submittedAt}

GET  /api/v1/evaluation/tasks?mine=true                evaluator queue
PUT  /api/v1/evaluation/tasks/{id}/marks               rubric marks, version
POST /api/v1/result-sets/{id}/compute | /approve | /publish
GET  /api/v1/me/results                                candidate's published results and scorecards
POST /api/v1/admission-cycles/{id}/merit-lists         generate (async) ; /{listId}/publish
```

## Async jobs

Long operations (bulk allocation, paper generation for 10k candidates, result computation, merit lists, scorecard
generation) use the 1.6 job pattern: `202 Accepted` + job resource with progress, cancellation, report downloads.

## Versioning and compatibility

Additive changes only within v1; breaking changes require `/api/v2` alongside v1 for a deprecation period. Delivery
API changes are backward compatible within an examination season (clients may be cached on candidate devices).

## Rate limits

Existing per-user/tenant limits plus delivery-specific limits sized for exam bursts (per attempt: responses ≤ 10/s,
sample code runs ≤ 1 per 10 s) and public application endpoints per IP.
