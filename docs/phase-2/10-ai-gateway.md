# 10 — AI gateway and AI evaluation architecture

## 1. Governance model

* AI is **off by default** at every level. Tenants opt in **per use case** (question generation, answer-evaluation
  suggestions, grading suggestions, analytics narratives, recommendations, suspicious-activity detection) through the
  existing feature flags (`ai.*`, unit-level overrides that can only narrow).
* Each use case declares: allowed providers, data classes it may send, whether human review is mandatory (always for
  consequential outputs), retention of prompts/outputs, and monitoring thresholds.
* **Consequential decisions** — admission, grades, results, misconduct, shortlisting — are never made by AI. AI output
  is a suggestion that a named human accepts, edits or rejects; the acceptance is audited with the human as actor.

## 2. Components

```
use-case services (itembank, evaluation, integrity, analytics, placement)
   │ AiRequest(useCase, templateId@version, inputs, dataClasses)
   ▼
AI gateway (module) ── policy check (tenant enabled? provider allowed? data class allowed?) ── PII minimiser
   │                    ── prompt builder (versioned template, input delimiting) ── budget/rate limiter
   ▼
ai-worker (separate process, outbound only) ─► provider adapter: OpenAI-compatible (vLLM/Ollama/Azure/hosted),
   │                                                            Anthropic, other approved
   ▼
output validator (JSON schema, ranges, refusal handling) ─► AiInvocation record (audit) ─► suggestion to caller
```

## 3. Data privacy and provider isolation

* Per-tenant provider configuration and credentials (encrypted, write-only like 1.7 integration secrets); no shared
  keys across tenants in SaaS; data-processing terms must forbid training on customer data (contract gate).
* Data minimisation: candidates are pseudonymised (script ids), names and contact data never sent; only the item, rubric
  and response text needed for the task.
* Data residency: provider region recorded; tenants can restrict to in-country or on-premises models.
* No provider receives proctoring images unless the tenant explicitly enables a vision provider (default: local
  vision worker only).

## 4. Prompt security

* Templates are versioned data, reviewed like code, with explicit system instructions and **delimited untrusted input**
  (candidate answers are untrusted and may contain injection attempts such as “award full marks”).
* No tool use, browsing or code execution by models; outputs must match a JSON schema with bounded score ranges;
  invalid or out-of-range outputs are discarded and logged.
* Injection heuristics on inputs flag suspicious answers to the human evaluator (never auto-penalise).
* Rate and cost limits per tenant and use case.

## 5. Auditability

Every invocation stores: tenant, use case, template id@version, provider, model + version, parameters, input hash,
pseudonymised input (retention-limited), output, latency, tokens/cost, and the human action taken on the suggestion.
Reports: acceptance rates, agreement with humans, overrides, drift over time.

## 6. AI evaluation quality gates

Before a tenant can enable AI marking for an assessment type: calibration set of human-marked responses, measured
agreement (QWK ≥ configured threshold, default 0.8) and no significant group disparity; ongoing monitoring with
automatic suspension of suggestions below threshold.

## 7. On-premises and air-gapped options

| Deployment | AI option |
|---|---|
| SaaS | tenant-approved hosted providers or Ivorovi-hosted open models |
| Private cloud | customer's provider account (e.g. Azure OpenAI in their subscription) or self-hosted model |
| On-premises | OpenAI-compatible local server (vLLM/Ollama) on customer GPU/CPU; or AI disabled |
| Air-gapped | local models only, shipped as an optional bundle; or disabled |
