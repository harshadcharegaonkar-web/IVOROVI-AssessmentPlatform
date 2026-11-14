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

## KI-75 independent verification (2026-11-14, decision D-8)

Fresh reproduction from `origin/main` **9a2a6ae**, which includes the merged search (`question_search`, migration
`V202611141010`). The build was not taken from a WS-B branch and **no production code was changed**. **This is a local
measurement, not CI evidence:** the benchmark needs the full compose stack (Keycloak, PostgreSQL, Valkey, RabbitMQ,
object storage) plus about 30 minutes of data generation and measurement, so it is not added as a CI job. Raw results:
`question-bank-benchmark-ki75-verification.json`.

**Both targets are met.** The worst search p95 is **130 ms** at 60,000 questions in the organization (target
< 250 ms) and **122 ms** at 100,000 (target < 300 ms).

### Setup

- **Machine:** cloud container with 4 vCPU (Intel Xeon @ 2.10 GHz) and 15 GB RAM; no other load.
- **Stack:** `deploy/compose` started from empty volumes (`docker compose down -v`, `scripts/dev-setup.sh --force`, `docker compose up -d`).
- **Versions:** PostgreSQL 16.15, Keycloak 26.8.1, Valkey 8.1, RabbitMQ 4.3, SeaweedFS 4.51, OpenJDK 21.0.12, Docker 29.9.2.
- **API instances:** **one**, running on the host from `./gradlew :backend:api:bootJar` at 9a2a6ae, with `java -jar ivorovi-api.jar --spring.profiles.active=dev` and `scripts/api-env.sh`.
- **API configuration:** default connection pool (20). Per-user rate limits were raised to 100,000 per minute (`IVOROVI_RATE_LIMIT_USER_READ/WRITE`), because the defaults throttle the creation phase.
- **Migrations:** Flyway applied all 29 to the empty database (latest `V202611141010`).
- **Client:** `python3` on the same machine, sequential requests with a real Keycloak bearer token (password grant of the benchmark administrator).

### Dataset

1. `python3 scripts/benchmark/import_benchmark.py --sizes 100` creates the separate benchmark organization
   `bench-imports`, tenant `0192e000-0000-7000-8000-0000000000be`. Synthetic data only.
2. `python3 scripts/benchmark/question_bank_benchmark.py --org-size 60000 --runs 100 --out verify-60k.json`
   builds the measured bank, bank `01a1d82a-e9c3-71d6-b8a4-72c8fe7bc1b7`:
   - 300 single-choice questions created through the API;
   - 19,700 true/false drafts generated in SQL by `FILL_SQL` in the script, with the same columns the service writes.
3. The same run then adds 2 filler banks of 20,000 generated drafts, so the organization holds **60,000** questions,
   and runs `ANALYZE`.
4. After the search measures, the run's import step commits 100 more drafts, so the bank holds 20,100.
5. `python3 scripts/benchmark/question_bank_benchmark.py --bank 01a1d82a-e9c3-71d6-b8a4-72c8fe7bc1b7 --org-size 100000 --runs 100 --out verify-100k.json`
   adds 2 more filler banks, bringing the organization to **100,000**, runs `ANALYZE` and measures again.

The generator is deterministic in content but not in ids:
- Text, tags and types are fixed functions of the row number (`"<label> <n>: explain the <word[n%20]> of the <word[7n%20]> when x equals <n%97> and y equals <n%89>"`, tag `word[n%20]`).
- About 5% of rows contain each common word.
- There is no random seed. Only the bank codes and row ids use random UUID prefixes, and they do not affect content or query plans.

### Measurement

Measured with `GET /api/v1/question-banks/{bank}/questions`, page size 25:
- 3 warm-up requests;
- then **100 sequential requests per measure**;
- p50, p95 and max of the end-to-end client latency.

| Measure | Query string |
|---|---|
| First page | `size=25` |
| Common word | `q=velocity` (about 1,000 matches in the bank, 5% of the organization) |
| Word + number | `q=momentum equals 42` |
| Rare phrase | `q=Bench question 19993` |
| Type filter | `type=SINGLE_CHOICE` (300 matches) |
| Tag filter | `tag=enzyme` |
| Deep page | `page=700` |
| Approved only | `approvedOnly=true` |
| Question detail | `GET /api/v1/questions/{id}` |

### Results (ms)

| Measure | 60,000 in org: p50 / **p95** / max | 100,000 in org: p50 / **p95** / max |
|---|---|---|
| First page | 10.5 / **13.7** / 16.6 | 7.2 / **9.1** / 9.9 |
| Common word | 111.9 / **129.7** / 147.3 | 102.7 / **122.4** / 133.0 |
| Word + number | 28.8 / **40.1** / 43.3 | 26.4 / **35.6** / 42.4 |
| Rare phrase | 26.7 / **38.5** / 43.8 | 12.4 / **17.1** / 18.3 |
| Type filter | 49.3 / **61.1** / 65.6 | 50.6 / **62.1** / 67.7 |
| Tag filter | 21.8 / **31.8** / 34.3 | 20.3 / **29.7** / 32.7 |
| Deep page (700) | 18.0 / **24.3** / 29.2 | 8.8 / **11.0** / 11.6 |
| Approved only | 7.4 / **10.6** / 14.0 | 6.5 / **9.4** / 22.8 |
| **Worst search p95** | **129.7** (target < 250 ✓) | **122.4** (target < 300 ✓) |
| Question detail | 63.0 / 84.7 / 101.1 | 92.8 / 122.8 / 156.9 |

Other results of the 60,000 run:
- creation through the API: p95 30.6 ms over 300 creations;
- bulk fill of the bank: 5.3 s;
- import preview (200 rows): 13.2 s, with outcomes as expected (100 valid, 50 exact and 50 near duplicates, 0 invalid);
- import commit: 5.9 s.

**Correctness check.** After the 100,000 run, API totals were compared with direct SQL counts on the bank. They were
equal for full text `velocity` (1,005), tag `enzyme` (1,000), type `SINGLE_CHOICE` (300) and all (20,100).

**Compared with the earlier run** (Session 2, 2026-11-09, branch commit `23f6130`; same script, different stack
instance and database):
- the worst p95 agrees at 100,000: 123 ms then, 122 ms now;
- at 60,000 it is higher now: 130 ms against 64 ms. The common-word query dominates; in the earlier run it took 31 ms
  at 60,000 and 123 ms at 100,000.

The common-word search reads every matching version of the organization through the full-text and trigram indexes,
then keeps the bank's. Its cost therefore follows the number of matching rows in the organization, not the bank size.
The 60,000 difference between the two runs was not investigated (query plans were not captured). The query remains well
inside both targets.
