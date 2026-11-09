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

**KI-75 (search under RLS), as found in 2.1.** Full-text search had the same RLS limitation: its cost grew with the
organization's total number of versions, not the bank's. All searches met the < 300 ms target at 20,000 questions per
bank, but the worst case was at the limit (299 ms) when the organization held 60,000.

## KI-75 fix: indexed, bank-scoped search (2026-11-09, branch `ws-b/m5-question-bank`)

**Cause.** `EXPLAIN ANALYZE` as the application role showed every search reading **every** `question_version` row of
the table, even an unfiltered first page. It used a sequential scan plus a hash join to the bank's questions, because
under RLS the planner may not evaluate the non-leakproof predicates (`@@`, `ILIKE`, the tag subquery) before the policy
predicate, so their indexes could not be used.

**Fix.** Migration `V202611131010__question_search` adds `question_search()` and three indexes. The function follows
the `question_near_duplicate` pattern:
- SECURITY DEFINER, with the RLS tenant predicate `current_tenant_id()` applied on both `question` and
  `question_version`; no tenant means no rows;
- limited to one bank (the service still checks bank access first);
- every value bound with `EXECUTE … USING`;
- returns only one page of question ids and the match count, never content;
- `EXECUTE` revoked from PUBLIC and granted to the application role.

The indexes:
- `question (tenant_id, bank_id, updated_at DESC, id)` for page order and counts;
- trigram GIN on `plain_text` for substring search;
- GIN on `question_tags_ci(tags)` for the case-insensitive tag filter.

The service then loads the page's rows by id under RLS. Results, filters, order and paging are unchanged.
`QuestionSearchIT` covers correctness and isolation: another organization, no tenant and another bank all return
nothing; the function returns ids and count only, and grants no PUBLIC execute.

**Run.** Same benchmark bank (20,100 questions) measured with `main` (5c3a211) and with the branch:
- at 60,100 questions in the organization (3 banks) and at 100,000 (5 banks);
- 60 requests per measure, after 3 warm-up requests;
- local compose stack, one API instance, 4 vCPU, real Keycloak token;
- `--org-size` tops the organization up with filler banks; `--bank` re-measures an existing bank.

The "main, 100,000" run used the database after the migration: the new indexes existed but main's query cannot use
them. Raw results: `question-bank-benchmark.json`.

| Search, p95 (p50) in ms | main, 60,100 in org | **branch, 60,100** | main, 100,000 in org | **branch, 100,000** |
|---|---|---|---|---|
| First page | 220 (173) | **24 (14)** | 111 (85) | **11 (9)** |
| Common word | 323 (258) | **31 (22)** | 261 (227) | **123 (104)** |
| Word + number | 352 (273) | **33 (23)** | 514 (450) | **52 (34)** |
| Rare phrase | 312 (269) | **16 (11)** | 538 (451) | **27 (16)** |
| Type filter | 44 (33) | **64 (49)** | 88 (66) | **61 (52)** |
| Tag filter | 307 (251) | **28 (21)** | 234 (178) | **27 (19)** |
| Deep page (700) | 237 (176) | **17 (12)** | 362 (282) | **11 (8)** |
| Approved only | 179 (148) | **12 (8)** | 149 (130) | **11 (7)** |
| **Worst p95** | **352** | **64** | **538** | **123** |
| Target | < 250 | < 250 ✓ | < 300 | < 300 ✓ |

Notes:
- Both KI-75 targets are met with a wide margin: worst p95 64 ms at 60,000 and 123 ms at 100,000.
- On main, this run exceeded the targets at both sizes (352 ms and 538 ms). That is worse than 2.1's 299 ms; the
  hardware differs.
- **Type filter:** the type filter has no index and is not selective here (2% of rows). It joins the bank's questions
  to their versions: about 60 ms, flat between 60,000 and 100,000.
- **Common word:** this query matches 5% of the organization's versions. Its cost grows with the matching rows in the
  organization (index hits are filtered to the bank afterwards), not with all versions.
- Other benchmark results at 60,100: creation p95 65 ms; import preview (200 rows) 12.5 s with outcomes as expected;
  commit 5.7 s; detail p95 86 ms. Detail at 100,000: 107 ms on the branch, 125 ms on main.
- The migration applied in 1.6 s on the 60,100-question organization; the indexes are built inside it.
