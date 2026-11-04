# 11 — Integration architecture

Builds on the 1.7 framework (adapter SPI, SSRF guard, API clients, signed webhooks) and the KI-59 model (live
permission intersection, service identities).

| Integration | Direction | Approach | Phase 2 scope |
|---|---|---|---|
| Institution SSO (D-P1-02, deferred to Phase 2) | in | Keycloak identity brokering (SAML 2.0 / OIDC) per tenant; JIT linking to existing accounts by verified email; no auto-creation of students (D-P1-03) | Yes |
| LMS | both | LTI 1.3 tool (launch an Ivorovi assessment from Moodle/Canvas/etc., Assignment and Grade Services for score passback, Names and Roles for rosters) | Yes (first connector) |
| Rosters | in | OneRoster 1.2 CSV/REST import into the 1.6 import pipeline (validation, duplicates, audit) | Yes |
| SIS / ERP | both | Canonical mapping layer (students, enrolments, results) + generic REST adapter; vendor connectors only with approved scope (KI-60) | Mapping layer; vendors on approval |
| Email | out | SMTP adapter (1.7) as the notification channel for admit cards, results, incidents (approval P2-09) | Yes |
| SMS / WhatsApp | out | Provider adapters | Only with provider approval and DPDP review |
| Payments (application fees) | both | Provider adapter with webhooks, reconciliation | Only with provider approval (P2-10) |
| Recruiters | out | Drive-scoped recruiter accounts or delegated API clients limited to `placement.recruiter.read`; consented data only | Yes |
| Webhooks | out | Event catalogue extended with assessment/result/admission/placement events (minimised payloads) | Yes |
| Identity verification services (e.g. national ID) | out | Not planned; manual/proctor verification only | No |

## Canonical data and mapping layer

Canonical schemas (Person, Student, Enrolment, ResultRecord) with field mappings per integration, dry-run diff,
conflict policy (Ivorovi wins / source wins / manual), per-record audit, idempotent upserts keyed by external ids, and
the same validation reports as the 1.6 import engine.

## Reliability

Outbound calls never inside business transactions; retries with backoff; dead-letter view per integration; KI-61 lease
for scheduled jobs; KI-64 publisher throughput work before examination events depend on it.
