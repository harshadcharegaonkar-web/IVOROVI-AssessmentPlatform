# WS-B status (Session B, branch `ws-b/m5-question-bank`)

## 2026-11-09 — KI-75 search optimization (work package 1)

**Done**
- Indexed, bank-scoped question search. `question_search()` is a SECURITY DEFINER function following the
  `question_near_duplicate` pattern:
  - applies the RLS tenant predicate `current_tenant_id()` on `question` and `question_version`;
  - limited to one bank;
  - all values bound;
  - returns only one page of ids and a count;
  - no PUBLIC execute.
- Three indexes: bank order; trigram on `plain_text`; case-insensitive tags.
- `QuestionService.search` keeps the bank access check and loads the page by id under RLS. Behaviour is unchanged.
- Merged to `main` by Session A (merge `6100884`).
- Benchmark script extended (`--org-size`, `--bank`, approved-only measure). The API benchmark was measured at 60,000
  and 100,000 questions in the organization (`../performance/question-bank-benchmark.md`, commit `23f6130`):
  - worst search p95 at 60,100: **64 ms** (main: 352 ms; target < 250);
  - worst search p95 at 100,000: **123 ms** (main: 538 ms; target < 300).

**Migrations**
- `V202611141010__question_search.sql`. Created as `V202611131010`; renumbered by Session A on `main` (`5019a3a`) under
  the integration rule.

**Tests**
- New `QuestionSearchIT` (2 tests):
  - results, order, paging, LIKE-wildcard escaping, approved-only with a newer draft;
  - keys and explanations are never matched;
  - isolation: another organization, no tenant and another bank return nothing; ids and count only; no PUBLIC execute.
- Full backend suite `./gradlew :backend:api:test` (Testcontainers) on the KI-75 commit rebased onto `5c3a211`:
  **433 tests, 0 failures**. Session A reports 442/0 on `main` after the merge.
- Frontend lint/typecheck/test: not run; no frontend or OpenAPI change.

**Open**
- KI-75 record in `known-issues.md`: request B-1 (`ws-b-requests.md`).
- M5 (blank, matching and ordering types, secure banks, QTI): **not started**; stopped at Session A's request.
- Reassignment of WS-B to scheduling ("Session 2"): requested by Session A by session message. WS-B is holding it until
  the decision is recorded on `main` (decision register and baseline §3/§6) or confirmed by the product owner
  (request B-2).
- Environment: Docker Hub is rate-limited here, so images are pulled through `mirror.gcr.io` and re-tagged.
  `dockerd` must be restarted after a worker restart.
