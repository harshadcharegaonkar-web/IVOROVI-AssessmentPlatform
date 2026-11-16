# Shared contract C-2: manual evaluation (owner: Session A)

Used by:
- Session A: the manual evaluation module (M-G);
- Session 4: AI-assisted evaluation (AO-A);
- Session 3: demo data step 30;
- Session 5: analytics, which reads released results only.

Builds on C-1 (`question-types-and-marking.md`):
- evaluation consumes `exam.attempt.awaiting_marks`;
- final marks go to exams through `ItemResults.record(…, Source.EVALUATION, …)`.

## 1. Module and permissions

- New Spring Modulith module `com.ivorovi.evaluation`. It uses only public APIs: `exams.ItemResults`, a new
  `questions.EvaluationMaterial`, `approvals.ApprovalService`, `AuditLog` and `Outbox`.
- Permissions (existing, PLANNED, now activated), scoped to the schedule's organization unit:
  - `evaluation.write`: mark the tasks allocated to oneself;
  - `evaluation.manage`: set up evaluation, allocate, moderate, and decide re-evaluation requests.

## 2. Anonymity (non-negotiable)

- Evaluators work on **scripts**, never on attempts. A script has a random code (for example `S-7KQ3-M9`).
- The mapping from script to attempt lives in `evaluation_script.attempt_id`. It is **never** returned to an
  evaluator: no candidate name, email, attempt id, portal or timing.
- Response text is shown as written. Session 3's audit scan checks for names.
- Evaluator identities are never shown to candidates.
- In double marking, the second evaluator does not see the first evaluator's marks.

## 3. Workflow

1. **Setup (per schedule, `evaluation.manage`).**
   - Mode: `SINGLE` or `DOUBLE`.
   - Moderation tolerance: a percentage of the item's marks; default 10.
   - Evaluator pool: users holding `evaluation.write` in the unit.
   - Setup can change only until the first mark is submitted.
2. **Scripts.**
   - The `exam.attempt.awaiting_marks` consumer creates one script per attempt, with one item per descriptive
     position.
   - Idempotent per attempt.
3. **Allocation.**
   - Automatic and balanced (fewest open tasks first, ties broken by a seeded order), or manual by a manager.
   - The same evaluator never marks one script twice.
   - DOUBLE gives two distinct evaluators per item.
4. **Marking (evaluator).**
   - The evaluator sees the question statement, the rubric, the model answer (an audited read, purpose
     `EVALUATION`) and the response text.
   - They enter per-criterion marks: each between 0 and weight × marks / 100, with at most 2 decimals. Without a
     rubric, one holistic mark between 0 and marks.
   - A comment is optional.
   - Submission is final for that task (append-only).
5. **Reconciliation.**
   - SINGLE: the submitted mark is final.
   - DOUBLE: if the two totals differ by at most the tolerance, the final mark is their mean, rounded half-up to 2
     decimals. Otherwise a moderation task opens for a manager, who is neither of the two evaluators. The moderator
     sets the final mark and must give a reason.
6. **Completion.** Each final item mark goes to `ItemResults.record`. The last one writes the attempt's score
   (PROVISIONAL). Review and independent approval (R-1) then publish it, unchanged.
7. **Re-evaluation (after publication).**
   - A candidate may request it within the schedule's window (default 7 days), per descriptive item, with a reason.
   - A manager accepts or rejects it, with a reason.
   - Accepted: a new evaluator, never an earlier one, marks the item afresh.
   - A changed total becomes an **append-only score revision**. Publishing that revision needs review and
     independent approval, exactly as R-1 (the published score never changes silently).
   - The candidate sees the outcome.
8. **Ranks and scorecard.**
   - Computed from released scores (or their approved revision) only.
   - Competition ranking (1, 2, 2, 4) per schedule.
   - The scorecard shows the candidate's own released result, rank, section totals and descriptive feedback where
     the institution enables it.

## 4. The API for AI-assisted evaluation (Session 4)

```java
// com.ivorovi.evaluation.EvaluationTasks (public module API)
record TaskView(UUID taskId, String scriptCode, UUID questionVersionId, BigDecimal marks,
                List<Criterion> rubric /* id, title, description, weight */, String responseText) {}
Optional<TaskView> forAssistance(UUID taskId);            // caller must be the task's evaluator; audited
// The AI side keeps its suggestion in its own tables. The marker form shows it.
// When the evaluator submits, evaluation records how the suggestion was used:
enum SuggestionUse { ACCEPTED, CHANGED, IGNORED }
void recordSuggestionUse(UUID taskId, UUID suggestionId, SuggestionUse use);   // called by the form's submit, actor = evaluator
```

- The model answer is **not** in `TaskView`. Whether it may ever be sent to an external provider is escalated to the
  product owner (Q9). The default is no.
- The marks are always the evaluator's own. A suggestion never submits a task.

## 5. REST (prefix `/api/v1/evaluation`, to be added to OpenAPI before the UI)

| Method and path | Who | Purpose |
|---|---|---|
| GET/PUT `/schedules/{id}/setup` | manage | Mode, tolerance, evaluator pool |
| POST `/schedules/{id}/allocate` | manage | Automatic allocation of open items |
| GET `/schedules/{id}/progress` | manage | Counts per state; tasks per evaluator (no candidate data) |
| GET `/tasks?status=` | write | My tasks (script code, question, state) |
| GET `/tasks/{id}` | write (own) | Statement, rubric, model answer (audited), response |
| POST `/tasks/{id}/submission` | write (own) | Criterion marks, comment |
| GET `/moderation` | manage | Open moderation items, with both marks |
| POST `/moderation/{id}` | manage, not an evaluator of it | Final mark and reason |
| GET/POST `/re-evaluations` | manage | List requests; decide one |
| POST `/delivery/portals/{key}/attempts/{id}/re-evaluations` | candidate | Request a re-evaluation (published results only) |
| GET `/schedules/{id}/ranks` | `exam.results.read` | Ranks from released scores |
| GET `/delivery/portals/{key}/attempts/{id}/scorecard` | candidate | Own scorecard |

## 6. Tests the module brings

- Anonymity: no evaluator payload contains a candidate's name, email or attempt id.
- Double marking within and outside the tolerance.
- The moderator is never one of the two evaluators.
- Bounds and decimals.
- Append-only guards.
- Tenant and unit isolation.
- Re-evaluation produces a revision that needs approval, and the published score stays unchanged until then.
- Ranks with ties.
- Browser E2E: evaluator marks → moderation → review and approval → candidate result.
