# 08 — Evaluation, moderation and results architecture

## 1. Evaluation plan (per assessment version)

For each item: `AUTO` (type handler scores it), `MANUAL` (rubric marking), `ASYNC` (coding verdicts), optionally
`AI_ASSISTED` (manual with AI suggestion). Plan options: anonymised marking (default on), single/double marking,
discrepancy tolerance (absolute or %), third-marker rule, moderation sample (%, minimum N, plus all borderline scripts),
conflict-of-interest exclusions (evaluator taught/related candidate flags), deadlines.

## 2. Flow

```
SUBMITTED ─► auto-score lines (AUTO) ─┬─► all AUTO? ─► EVALUATED
                                      └─► tasks for MANUAL/ASYNC items
tasks ─► allocation (load-balanced per evaluator capacity and subject) ─► marking (rubric per criterion, annotations)
      ─► double marking? ─► discrepancy > tolerance ─► third marker / moderator
      ─► moderation sample ─► moderator adjusts (reason required) ─► controller locks marks ─► EVALUATED
```

* Evaluators see responses **without identity** (pseudonymous script id) unless the plan says otherwise.
* Every score line records actor, time, criterion scores, reason for changes; earlier lines stay in history.
* Re-evaluation requests (candidate, within a window, optionally fee-based later) create new tasks for a different
  evaluator; outcomes can raise or lower marks per policy; audited.

## 3. AI-assisted marking (optional)

* The AI gateway produces `AiSuggestion` (score per criterion, rationale, confidence) — never a score line.
* Anchoring control: tenant chooses whether evaluators see the suggestion before or after their own mark (default:
  after, then the evaluator may revise with a reason).
* Agreement monitoring per assessment: AI–human agreement (quadratic weighted kappa), acceptance rate, bias checks
  across groups with enough data; if agreement drops below a configured threshold the suggestions are hidden and
  controllers alerted.
* Moderators sample AI-accepted scores at a higher rate than human-only scores (default 2×).

## 4. Results

| Step | Detail |
|---|---|
| Compute | totals, section totals, negative marking, caps; optional scaling; optional normalisation across sessions (percentile-equating, documented formula); percentiles; ranks with tie-break chain (e.g. section score → fewer negatives → age) |
| Review | distribution statistics, item analysis, outliers, incidents pending, unresolved re-evaluations block approval |
| Approve | `result.approve` holder; four-eyes (approver ≠ computer) configurable, default on for ADMISSION and SEMESTER |
| Publish | `result.publish`; scorecards generated (PDF, signed hash + verification URL), notifications, webhook `result.published` (no scores in payload by default) |
| Revise | new revision with reason; previous revision preserved; consumers notified |
| Queries | candidates raise result queries; workflow with SLA; outcomes audited |

## 5. Rankings and merit lists (admissions)

Category-wise and overall merit computed from published results + stage results (interviews, document verification)
using the cycle's rule set (eligibility, weightages, reservation categories, tie-breakers). Merit lists are reproducible
from inputs and versioned; publication is four-eyes and audited; offers reference the merit-list version.

## 6. Scorecards

Configurable templates per purpose (admission scorecard, internal marks statement, placement employability report with
section percentiles and competency bands). Generated server-side; recruiters receive only consented scorecards.
