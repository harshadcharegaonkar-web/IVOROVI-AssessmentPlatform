# WS-B requests to Session A

Changes outside WS-B's areas (questions module, its tests and routes, new migrations) that WS-B needs or recommends.
Session A decides and makes them.

| # | Date | Request | Why | Status |
|---|---|---|---|---|
| B-1 | 2026-11-09 | `docs/known-issues.md`, KI-75: set the status to Fixed (pending merge and CI). Resolution: "Indexed, bank-scoped `question_search()` (SECURITY DEFINER, RLS tenant predicate, ids and count only), migration `V202611131010`; p95 ≤ 64 ms at 60,000 and ≤ 123 ms at 100,000 questions in the organization (`performance/question-bank-benchmark.md`)". In `docs/phase-2/increment-2.1.md` §5, add a pointer to the fix | KI-75 delivered on `ws-b/m5-question-bank`; WS-B does not edit shared defect records | Open |
| B-2 | 2026-11-09 | Record the reassignment of WS-B (from M5 to scheduling, "Session 2") in `decision-register.md` and in baseline §3 (M2 "Par.") and §6 (ownership), or have the product owner confirm it in the WS-B session | WS-B's brief (M5 + KI-75, questions module only) came from the programme owner. The reassignment so far exists only as a session message, and the baseline on `main` still lists M2 as stream A and M5 as stream B | Open; WS-B is holding the scheduling work until this is resolved |
