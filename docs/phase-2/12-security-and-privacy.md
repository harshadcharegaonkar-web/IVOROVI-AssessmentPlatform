# 12 — Security and privacy assessment (Phase 2)

## Threat model (new assets)

| Asset | Threats | Key controls |
|---|---|---|
| Question papers and answer keys | leak before exam, insider access, client-side extraction | secure banks, field-encrypted keys, blind assembly, paper released per attempt at start, no keys in client, access audit, watermarking candidate view with attempt id |
| Live attempts | impersonation, collusion, time manipulation, replay | candidate auth + allocation + access codes, device binding, server deadline, journal with sequence numbers, sealed submission |
| Responses and scores | tampering by staff, lost answers | append-only journal, score history with reasons, four-eyes publication, reproducible computation, hash-chained audit |
| Proctoring evidence | privacy harm, misuse | minimal collection levels, encryption, short retention, access audit, no recruiter sharing, no automated decisions |
| Candidate personal data | over-collection, breach | form schema minimisation, field encryption (data key RB-2/RB-3), purpose limits, retention jobs, DPDP rights workflow (RB-5) |
| AI data flows | leakage to providers, prompt injection | per-tenant providers, pseudonymisation, delimiting, schema-validated outputs, no tools |
| Code execution | sandbox escape, crypto-mining, data exfiltration | ADR-0004 isolation, no network, limits, separate zone without DB credentials |
| Recruiter access | over-sharing | drive-scoped access, consent records, expiry, audit |
| Machine access | stale privileges (KI-59) | live intersection, service identities |

## Privacy (DPDP-first, GDPR-ready)

* Notices and consent per purpose: application processing, examination (incl. proctoring level), result publication,
  recruiter sharing, AI processing (where personal data is involved).
* Data-principal rights (access, correction, erasure subject to statutory retention) — the RB-5/KI-48 workflow becomes
  a Phase 2 prerequisite before real candidates apply.
* Children's data: applicants may be minors (e.g. 17-year-olds); verifiable parental consent flow required under DPDP
  for under-18 candidates — **legal review needed (P2-11)**.
* Retention schedule per data class (`18-business-readiness.md`); deletion jobs and evidence of deletion.

## Release blockers (carried forward, unchanged)

RB-1 real malware scanner in staging (documents, imports, application uploads, item media); RB-2/RB-3 data-key
backup/recovery/rotation and rotation tooling; RB-4 KI-46 student account lifecycle; RB-5 KI-48 DPDP legal review and
rights workflow; RB-6 penetration test. **No Phase 2 feature is production-ready until these are closed.** New Phase 2
blockers: ADR-0004 PoC before coding assessments; exam-delivery load and chaos tests before any live exam; AI quality
gate before AI marking; proctoring fairness evaluation before L3.

## Security testing per increment

Authorization matrix tests per new permission, cross-tenant tests for every table, answer-key leakage tests (no key in
any candidate response payload), replay/idempotency tests, time-manipulation tests, chaos tests for delivery, DAST on
candidate and staff apps, dependency and image scanning (existing CI).
