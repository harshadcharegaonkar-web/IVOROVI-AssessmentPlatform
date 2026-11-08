# Question bank benchmark (Increment 2.1)

Script: `scripts/benchmark/question_bank_benchmark.py`. Local compose stack, single API instance, `dev` profile, real
Keycloak token. Run in the separate benchmark organization (`bench-imports`), with synthetic content only. Per-user rate
limits were raised for the run (`IVOROVI_RATE_LIMIT_USER_WRITE/READ=100000`); the defaults (60 writes per minute) would
otherwise throttle the creation phase. Raw results: `question-bank-benchmark.json` (last run).

Bank of **20,000 questions**: 300 created through the API, the rest generated in SQL as drafts. The generated rows use
the same columns the service writes (search vector, normalized text, duplicate hash), with varied but templated text,
which is adversarial for trigram pruning.

| Measure (p95 unless noted) | Run 1 — 20,000 questions in the organization | Run 3 — 60,000 in the organization (3 benchmark banks) | Target (plan §13) |
|---|---|---|---|
| Create question via API (validation, encrypted key, audit) | 55 ms (p50 34 ms) | 105 ms (p50 82 ms) | — |
| Search: first page | 142 ms | 226 ms | < 300 ms |
| Search: common word | 139 ms | 271 ms | < 300 ms |
| Search: word + number | 129 ms | 299 ms | < 300 ms |
| Search: rare phrase | 133 ms | 286 ms | < 300 ms |
| Search: type filter | 48 ms | 68 ms | < 300 ms |
| Search: tag filter | 124 ms | 271 ms | < 300 ms |
| Search: deep page (700) | 135 ms | 241 ms | < 300 ms |
| Question detail | 62 ms | 81 ms | — |
| Import preview, 200 rows (validation, exact and near duplicates) | 63.5 s (before fix) | **12.2 s** (61 ms per row, everything included) | near-duplicate check < 50 ms per row |
| Near-duplicate query alone | ~600 ms (sequential scan) | < 40 ms (never above the 40 ms slow-query log threshold) | < 50 ms |
| Import commit, 100 drafts | 3.1 s | 5.7 s | — |

Import outcomes in both runs were as expected: 100 valid, 50 exact duplicates, 50 near duplicates, 0 invalid.

**Finding fixed during the benchmark.** Under row-level security PostgreSQL does not use the trigram index, because
the `%` operator is not leakproof, so the near-duplicate check scanned every version in the organization.
Migration `V202611121001` adds `question_near_duplicate`, a SECURITY DEFINER function that:
- applies the same tenant predicate as the RLS policy (`current_tenant_id()`; no rows outside a tenant);
- is limited to one bank;
- sets the 0.85 threshold transaction-locally;
- returns only an id and a similarity score.

Isolation is tested in `QuestionIsolationIT`.

**Open (KI-75).** Full-text search has the same RLS limitation: its cost grows with the organization's total number of
versions, not the bank's. All searches meet the < 300 ms target at 20,000 questions per bank, but the worst case is at
the limit (299 ms) when the organization holds 60,000. The planned mitigation is an equivalent tenant- and bank-scoped
function for search, to be done before banks of this size are used in production.
