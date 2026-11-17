# Cross-module API sweep: tenant isolation and authorization

Owner: Session 3. The test is `ApiIsolationSweepIT` (`securityregression`). It covers every operation of the committed OpenAPI document (`docs/api/openapi.json`), so a new endpoint is covered without a new test.

Organization A is the `DeliveryJourneySupport` fixture: a bank, questions, a published assessment, a portal, a schedule, enrolled candidates, an attempt and its approvals. Every id path parameter is filled with a real record of organization A of that kind, or a random id where the fixture has none.

| Check | Rule | Result (2026-11-17, main b297657) |
|---|---|---|
| **Isolation:** an administrator of organization B | No 2xx on any operation addressed at A's records; no list it reads contains an id of A; no 5xx | 214 addressed operations and 68 lists: **no leak**. 3 operations answered 500 before the QA-DP-2 fix, none after |
| **Authorization:** organization A accounts with no role, and with only STUDENT | No 2xx on any mutating operation, nor on any read addressed at a record (self-service excepted); no 5xx | 490 calls: **none allowed**. 2 operations answered 500 before the QA-DP-2 fix, none after |

Real records of A were used for 15 kinds: assessment papers, assessments, banks, candidates, codes, enrolments, org units, portals, question banks, questions, results, role assignments, schedules, sessions, users. Academics, students, staff and integrations are not in the fixture yet; those operations ran with random ids. Extending the fixture, or running the sweep against the demo dataset, is the next step.

Accepted as neutral:
- `GET /api/v1/academics/staff/{staffProfileId}/teaching` answers `200 []` for any id, the same for a random one. It is a filter under RLS, with no data and no existence oracle.

Limits:
- Mutations are sent with an empty JSON body. A 400 proves only that nothing changed, not the order of the checks.
- Lists are compared by id.

## Findings

| Id | Severity | Area (owner) | Finding | Status |
|---|---|---|---|---|
| QA-DP-2 | Low | `integrations` web (S3); `approvals` web (shared, A) | 22 request bodies had no `@Valid`. Their constraints (`@NotNull`, `@Size`, `@Pattern`, `@Min`/`@Max`) were not enforced, because a class-level `@Validated` does not cascade into an unannotated `@RequestBody`. A missing field answered **500** (NullPointerException) instead of 400: `PUT /integrations/{id}/enabled`, `PUT /webhooks/{id}/status`, `POST /approvals/{id}/decision`. Length limits fell through to the services or the database. No data impact found: the services re-check the security-relevant values, such as the API key rotation grace of 0–168 h and the webhook status | **Fixed** on `ds/s3-qa-sweep`. `@Valid` added to all 22 bodies: integrations commit (own module); approvals as a separate flagged commit. The sweep's no-5xx rule covers it |
