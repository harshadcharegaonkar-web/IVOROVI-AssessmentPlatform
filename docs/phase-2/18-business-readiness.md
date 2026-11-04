# 18 — Business readiness

## SaaS licensing implications

| Element | Proposal |
|---|---|
| Packaging | Core (institution management, Phase 1) + modules: Admissions, Examinations (engine + delivery), Evaluation, Proctoring, Coding, AI Assistance, Placement, Integrations |
| Metering | candidates assessed per year (attempts), proctored attempts, coding executions, AI tokens/invocations, storage beyond allowance |
| Enforcement | entitlements checked server-side (module flags from the `licensing` module feed the existing feature resolver); soft limits with warnings, hard limits only for metered AI/coding cost |
| On-premises | signed offline licence file (modules, candidate cap, expiry), verified at start-up and daily; grace period |
| Third-party licences | review of every new runtime dependency (e.g. vision models, LLM weights, code-runner images) for licence compatibility before bundling — especially on-premises distribution (cf. ADR-0004 GPL note) |

## Tenant-level feature configuration

Extends the Phase 1 feature model (organization → university → college; overrides only narrow): modules, purposes
enabled, integrity levels allowed, AI use cases and providers, four-eyes policies, result-publication policies,
retention overrides (within legal limits), notification channels, branding of candidate portal and scorecards.

## Private-cloud and on-premises deployment

See `16-deployment-architecture.md`: single-tenant installs, Helm or Compose bundle, air-gapped installer, local AI or
none, gVisor code execution, customer-managed keys (data key in customer KMS/HSM), signed upgrades.

## Institutional integrations

SSO, LMS (LTI 1.3), rosters (OneRoster), SIS/ERP via mapping layer, email, webhooks, read-only APIs with service
identities (KI-59); payments and SMS after provider approval.

## Reporting requirements

Operational: application funnel, eligibility outcomes, attendance per session, disconnections, incidents, evaluation
progress, result distributions, item analysis, merit-list statistics, placement funnel. Regulatory/institutional:
category-wise admission statistics, results registers, audit extracts. All via the 1.7 report framework (scoped, audited,
personal columns on request only).

## Data retention and privacy (proposed defaults, subject to legal review P2-11)

| Data class | Default retention | Then |
|---|---|---|
| Applications of non-admitted candidates | 1 year after cycle close | delete; aggregate statistics kept |
| Admitted candidates | becomes student record (Phase 1 policy) | — |
| Question papers / item versions | life of bank + 5 years after last use | archive |
| Response journal | until results final + appeal window (90 days) | keep latest responses + seal for 3 years (semester: per university regulation), delete journal detail |
| Proctoring evidence (snapshots, signals) | 90 days after results final, or until incident/appeal closes | delete |
| Scores, results, merit lists | per institution regulation (default 10 years) | archive |
| AI invocation inputs/outputs | 1 year (inputs pseudonymised) | delete; metadata kept |
| Audit trail | 7 years | archive |

## Operational monitoring

SLOs (save latency, submission success, availability during exam windows), exam calendar-driven capacity planning,
synthetic probes, alerting on queue depth/runner latency/AI errors, cost dashboards (AI, compute), security monitoring
(auth failures, API-key denials, SSRF refusals, flagged prompt injections).

## Support and audit requirements

Support tiers with exam-day priority line; incident severity model (S1 = live exam impaired); runbooks per failure
mode; tenant-approved, time-boxed support access (P2-08); customer-facing audit extracts; post-exam reports for
institutions (attendance, incidents, technical events); evidence packs for appeals (sealed responses, timeline,
decisions).
