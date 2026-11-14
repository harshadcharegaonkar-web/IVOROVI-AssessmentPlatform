# KI-75 — independent rerun of the question-search benchmark (D-12)

Run 2026-11-14, 17:22–17:33 UTC, by **Session 3 (QA)**, session `session_019xkx8oYw3Yct98ZXZJrXg3` (D-7, D-12).
KI-75 stays **open** until the primary session has reviewed this evidence.

## Independence

- **Commit:** `origin/main` at `08d1c3f` (detached checkout; API jar built from it with `./gradlew :backend:api:bootJar`), not a
  `ws-b/` branch. The KI-75 change under test is `cb5817b` / `6100884` (function `question_search`, migration `V202611141010`).
- **Fresh data:** `docker compose --profile app down -v` (all volumes removed, including `pgdata`), infrastructure started again,
  Flyway migrated the empty database on API start. No earlier database, volume or dump reused.
- **No reuse of Session 2's results:** none of their result files were read or copied. The dataset was generated in this run.
  Session 2's own percentiles were not consulted for these numbers.
- **Own measurement:** `docs/performance/ki75-rerun-measure.py`, written for this rerun (own HTTP client, warm-up, request
  counts, nearest-rank percentiles, concurrent pass). It shares only the token helper of `scripts/demo/demo_data.py`.
  Additional database-side timings were run as SQL in PostgreSQL (below).

## Environment

| Item | Value |
|---|---|
| Machine | Cloud container: 4 vCPU Intel Xeon @ 2.10 GHz, 15 GB RAM. The API, PostgreSQL, Valkey, Keycloak and the load generator share it |
| Database | PostgreSQL 16.15 (compose image `postgres:16.15`), default settings of the compose stack |
| Other services | Valkey 8.1.11, Keycloak 26.8.1, RabbitMQ 4.3, SeaweedFS 4.51, Mailpit (compose) |
| API | OpenJDK 21.0.12, `ivorovi-api.jar` from `08d1c3f`, run natively (`java -jar`, `SPRING_PROFILES_ACTIVE=dev`, ADMIN process role). Compose images could not be built in this environment (Alpine package mirrors are blocked by the sandbox proxy), so the API ran as a JVM next to the compose infrastructure with the same configuration |
| Rate limits | Raised for the run (`IVOROVI_RATE_LIMIT_USER_READ/_USER_WRITE/_TENANT=1000000`). The default 300 reads/min per user would throttle 300 sequential plus 300 concurrent requests per query type. Nothing else changed |
| Client | Python 3 `urllib` on the same machine, `http://localhost:8080`, real Keycloak access tokens of `bench.admin@benchmark.test` |

## Dataset and how it was generated

1. `scripts/benchmark/import_benchmark.py --sizes 100` creates the separate benchmark organization "Import Benchmark Org"
   (`bench-imports`, tenant `0192e000-…-0be`) and its administrator (no demonstration or test organization is touched).
   Its 100-row student import is incidental.
2. `scripts/benchmark/question_bank_benchmark.py --size 20000 --creates 30 --runs 30 --org-size 60000` (read before use):
   - creates one bank (`01a1db18-317e-7a62-a56e-2c48cec10435`) and 30 questions through the API (single-choice, approved);
   - fills the bank to 20,000 questions with SQL-generated drafts. Each has realistic text ("Bench question i: explain the
     \<word\> of the \<word\> when x equals … and y equals …", 20 subject words), a search vector, tags and duplicate-detection
     data;
   - adds filler banks (SQL) until the **organization** holds 60,000 questions, then runs `ANALYZE`;
   - runs its own search measurement (30 runs per query, p50/p95/max) and a 200-row import preview/commit;
   - rerun with `--bank <same bank> --org-size 100000 --runs 30` tops the organization up to **100,000** (`ANALYZE` again).
3. Verified by direct SQL counts: organization questions 60,100 (with the 100 imported rows), then **100,000**; bank 20,100;
   one version per question. The searched bank is a fifth of the organization's questions, the case KI-75 is about:
   cost used to grow with the organization, not the bank.

## Query types and method

All on `GET /api/v1/question-banks/{bank}/questions` (page size 25) with the same filters as the original benchmark:

| Query | Parameters | Matches in the bank |
|---|---|---|
| first_page | none | 20,100 |
| word_common | `q=velocity` | 1,006 |
| word_and_number | `q=momentum equals 42` | 43 |
| phrase_rare | `q=Bench question 19993` | 1 |
| type_filter | `type=SINGLE_CHOICE` | 30 |
| tag_filter | `tag=enzyme` | 1,000 |
| deep_page | `page=700` | 20,100 |
| approved_only | `approvedOnly=true` | 0 (the generated drafts are not approved: this measures the empty case) |

Per query type: 20 warm-up requests (not counted), then **300 sequential** requests, then **300 requests from 8 concurrent
clients**. Every response was checked to be 200 with an item page. Percentiles use the nearest rank over all requests,
end-to-end at the client (token check, rate limiter, row-level security, query, JSON).

## Results — API (this rerun's measurement), milliseconds

**60,000 questions in the organization**

| Query | seq p50 | seq p95 | seq p99 | 8 clients p50 | 8 clients p95 | 8 clients p99 |
|---|---|---|---|---|---|---|
| first_page | 12.0 | 16.5 | 20.5 | 24.7 | 39.7 | 50.3 |
| word_common | 20.0 | 27.2 | 35.6 | 51.0 | 83.6 | 102.2 |
| word_and_number | 20.4 | 27.4 | 28.9 | 41.8 | 77.3 | 91.2 |
| phrase_rare | 18.7 | 24.9 | 28.4 | 41.3 | 68.6 | 96.0 |
| type_filter | 41.4 | 55.6 | 60.6 | 67.0 | 113.1 | 145.7 |
| tag_filter | 17.7 | 24.4 | 27.0 | 40.0 | 78.4 | 97.5 |
| deep_page | 10.0 | 11.7 | 12.8 | 21.4 | 32.6 | 43.6 |
| approved_only | 7.8 | 9.4 | 10.2 | 20.0 | 31.9 | 37.7 |

**100,000 questions in the organization**

| Query | seq p50 | seq p95 | seq p99 | 8 clients p50 | 8 clients p95 | 8 clients p99 |
|---|---|---|---|---|---|---|
| first_page | 7.2 | 9.9 | 12.4 | 19.1 | 33.4 | 43.1 |
| word_common | 21.1 | 28.1 | 29.0 | 44.8 | 75.9 | 91.3 |
| word_and_number | 25.2 | 31.6 | 32.5 | 41.3 | 77.1 | 99.5 |
| phrase_rare | 21.8 | 28.7 | 32.6 | 39.6 | 68.0 | 76.9 |
| type_filter | 55.6 | 74.4 | 78.1 | 115.5 | 168.2 | 186.2 |
| tag_filter | 18.2 | 25.1 | 28.4 | 40.1 | 63.9 | 84.2 |
| deep_page | 8.7 | 11.2 | 13.0 | 21.8 | 36.4 | 40.9 |
| approved_only | 7.8 | 9.6 | 11.6 | 16.5 | 26.0 | 29.2 |

Raw: `ki75-rerun-60k.json`, `ki75-rerun-100k.json`.

**Against the targets** (p95 < 250 ms at 60,000, < 300 ms at 100,000): every query type meets them sequentially (worst
p95: type filter, 55.6 and 74.4 ms) and with 8 concurrent clients (worst p95 113 and 168 ms; worst p99 146 and 186 ms).
The type filter is the slowest query and the only one that grows clearly between 60,000 and 100,000 (seq p95 55.6 → 74.4 ms).

**The benchmark script's own measurement** (same runs, 30 requests per query, p50/p95/max, measured right after the bulk
insert, before my warm-up): at 60,000, word_common p50/p95/max 125 / 159 / 168 ms, all other queries ≤ 63 ms p95. At 100,000,
every query ≤ 80 ms p95 (type filter 65.8 / 80.4 / 82.8). The higher 60,000 word_common figure came from the first queries after
40,000 rows had just been inserted; a cold cache is the likely cause (not verified separately). It is still within the target
and did not recur after warm-up (my 300 runs: p95 27.2 ms).

## Independent check — database side, and before/after the fix

Timed inside PostgreSQL as the application role (`ivorovi_app`, row-level security active, tenant bound with
`set_config('app.tenant_id', …)` as the API does). 20 warm-up and 200 timed calls of `question_search(...)` with the API's
parameters (bank, page size 25, offset 0). The pre-fix query (`QuestionService.list` before `cb5817b`: a `count(*)` and the
page query on `question ⋈ question_version` under RLS, with `@@ websearch_to_tsquery` / `ILIKE`) was run on **the same data**
(10 warm-up and 60 timed count + page pairs). Milliseconds, 100,000 questions in the organization:

| Query | before p50 | before p95 | before p99 | after p50 | after p95 | after p99 |
|---|---|---|---|---|---|---|
| word_common (`velocity`) | 152.0 | 210.0 | 232.0 | 12.1 | 17.8 | 22.2 |
| word_and_number (`momentum equals 42`) | 373.0 | 432.7 | 447.5 | 12.5 | 17.2 | 17.4 |
| list (no filter: count + first page) | 98.2 | 148.6 | 168.8 | 0.9 | 1.0 | 1.0 |

At 60,000 (after only): word_common 10.3 / 11.9 / 12.2, word_and_number 7.7 / 9.2 / 12.2, list 0.8 / 1.1 / 1.6.

`EXPLAIN (ANALYZE, BUFFERS)` of the pre-fix common-word count at 100,000: a **sequential scan of `question_version`** reading
all 100,000 rows of the organization (filter on the tenant rule plus `search_vector @@ … OR plain_text ~~* …`, 94,999
rows removed, 12,502 buffers) joined to the bank's 20,100 questions. Execution time 130.6 ms for the count alone. That is the
KI-75 mechanism (non-leakproof predicates under RLS cannot use the indexes; cost grows with the organization). After the
fix, the same search takes 12–22 ms in the database, with no growth from 60,000 to 100,000 beyond noise.

Under concurrency, the API's end-to-end time is dominated by work outside the query (token verification, rate limiter
round trip, JSON): the database share of word_common is about 12 ms of the 21 ms sequential p50.

## Limitations

- One machine shared by the load generator, the API and the database: absolute numbers are higher than on separated
  production hardware, and the concurrent pass measures contention on 4 vCPUs as much as the query.
- Generated text with a 20-word vocabulary; real question text may have different term frequencies. `approved_only` measures
  an empty result (generated rows are drafts).
- Single run per size (time-boxed to 30 minutes, D-12); no repeated sessions or confidence intervals.
- The "before" numbers are database-side only (the old jar does not run against the current schema); the "after" API numbers
  include the full request path.
