# Shared contract C-1: question types, marking modes and the scoring hook (owner: Session A)

Applies to:
- Session A: descriptive type;
- Session 4: fill-in-the-blank, matching, ordering;
- Session 2: coding.

Extends the existing plug-in interface `questions.internal.types.QuestionType`; nothing is replaced.

## 1. Adding a type

- One class implementing `QuestionType`, registered in `QuestionType.all()`.
- Storage does not change: type code, JSON body, encrypted JSON key, JSON marking.
- The body never contains answer information. `deliveryProjection` is all a candidate ever sees. The key is read only
  through `AnswerKeyStore` (ADR-0009).

## 2. New: marking mode and response validation (Session A, branch `ds/sA-descriptive`)

```java
enum MarkingMode { AUTOMATIC, MANUAL, EXTERNAL }
default MarkingMode markingMode() { return MarkingMode.AUTOMATIC; }
boolean acceptsResponse(Map<String, Object> body, Object value);   // every type; value is never null
```

- Delivery validates every saved answer through `QuestionMarking.accepts(type, body, value)`, which asks the type.
  The hard-coded switch in `AttemptService` is gone. **A new type needs no change in `exams`.**
- `QuestionMarking.Run.mark` returns `AWAITING_EVALUATION` or `AWAITING_EXECUTION` for a non-automatic type with a
  non-blank answer. A blank answer is `UNANSWERED` and needs nobody.
- The staff marking preview refuses non-automatic types (`409 not_automatically_marked`).

| Mode | Types | Scoring behaviour |
|---|---|---|
| AUTOMATIC | Existing types; fill-in-the-blank, matching, ordering | `mark(...)` as today, deterministic |
| MANUAL | Descriptive (essay, short answer) | `mark(...)` is never called. Scoring records the item as `AWAITING_EVALUATION` and opens an evaluation task in the manual-evaluation workflow (G, Session A). The marks awarded come from evaluators (and moderation), never from the type |
| EXTERNAL | Coding | `mark(...)` is never called. Scoring records `AWAITING_EXECUTION`. The coding module runs the submission in the isolated runner and reports a verdict through the hook below |

## 3. The scoring hook (exams module, Session A)

```java
// com.ivorovi.exams.ItemResults (exams module public API)
enum Source { EVALUATION, EXECUTION }
record Item(UUID attemptId, int position, UUID questionVersionId, BigDecimal marks, Source source, BigDecimal awarded) {}
List<Item> deferred(UUID attemptId);
boolean record(UUID attemptId, int position, BigDecimal awarded, Source source, String detail);  // true: the score is now complete
```

- **Deferral.** Scoring writes `exam_attempt_deferred` (positions per source) once, audits `exam.attempt.deferred` and
  emits the outbox event `exam.attempt.awaiting_marks` {attemptId, scheduleId, evaluation, execution}. Manual
  evaluation (G) and coding (AO-D) consume it.
- **Recording.** `exam_item_mark` is written once per attempt and position (append-only, no UPDATE or DELETE for the app
  role, guard trigger, RLS). Same marks again: a no-op. Different marks: `409 already_marked`; a change goes through
  re-evaluation (G). Outside 0…marks or with more than two decimals: `400`. Wrong source or a position that is not
  deferred: `400 not_deferred`. Audited as `exam.item.marked` (position, source, marks; no personal data).
- **Completion.** The last recorded mark writes the attempt's score at once, complete and immutable, as before.
  `exam_answer_score.source` says where each answer's marks came from. Only then is the result PROVISIONAL and can be
  reviewed and published (R-1). Until then the staff results show "awaiting evaluation" (`awaitingMarks`).
- Callers run in the attempt's tenant, in their own transaction, and authorize their own actor first.

## 4. The descriptive type (Session A)

- Code `DESCRIPTIVE`, MANUAL.
- Body `{maxWords}` (10–2,500, default 500); the candidate sees only that.
- Key `{modelAnswer?}`: guidance for evaluators, encrypted like every key, never delivered.
- Marking `{rubric?: [{id C1…Cn, title, description?, weight}]}`: 1–12 criteria, weights summing to 100 (percent of
  the item's marks). Without a rubric, evaluators mark holistically.
- Response: text within the word limit.

## 5. Delivery (exam app)

- Each type adds one answer component in `frontend/apps/exam/src`.
- Answers are saved through the existing journal unchanged: `response` is free JSON validated by the type.
- No new delivery endpoint.

## 6. Tests every new type brings

Each type must ship with:
- type unit tests: body, key and marking validation; the delivery projection contains no key;
- `mark(...)` cases (AUTOMATIC types);
- an integration test through authoring → publication → delivery → scoring;
- the security check that no key reaches any delivery payload (`AttemptIT`'s existing assertion extended).
