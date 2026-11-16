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

## 2. New: marking mode (added by Session A, Day 2)

```java
enum MarkingMode { AUTOMATIC, MANUAL, EXTERNAL }
default MarkingMode markingMode() { return MarkingMode.AUTOMATIC; }
```

| Mode | Types | Scoring behaviour |
|---|---|---|
| AUTOMATIC | Existing types; fill-in-the-blank, matching, ordering | `mark(...)` as today, deterministic |
| MANUAL | Descriptive (essay, short answer) | `mark(...)` is never called. Scoring records the item as `AWAITING_EVALUATION` and opens an evaluation task in the manual-evaluation workflow (G, Session A). The marks awarded come from evaluators (and moderation), never from the type |
| EXTERNAL | Coding | `mark(...)` is never called. Scoring records `AWAITING_EXECUTION`. The coding module runs the submission in the isolated runner and reports a verdict through the hook below |

## 3. The scoring hook (exams module, Session A)

```java
// exams module public API
public interface ItemResults {
  /** Records the final marks of one item of one attempt from a non-automatic source (evaluation or execution). */
  void record(UUID attemptId, int itemNo, BigDecimal awarded, String source /* EVALUATION | EXECUTION */, String detail);
}
```

- **Idempotent** per attempt, item and source.
- **Refused once the attempt's result is released** (R-1). A change after release goes through re-evaluation (G).
- An attempt's score becomes **PROVISIONAL only when every item has marks**. Until then the result row shows
  "awaiting evaluation" or "awaiting execution".

## 4. Delivery (exam app)

- Each type adds one answer component in `frontend/apps/exam/src`.
- Answers are saved through the existing journal unchanged: `response` is free JSON validated by the type.
- No new delivery endpoint.

## 5. Tests every new type brings

Each type must ship with:
- type unit tests: body, key and marking validation; the delivery projection contains no key;
- `mark(...)` cases (AUTOMATIC types);
- an integration test through authoring → publication → delivery → scoring;
- the security check that no key reaches any delivery payload (`AttemptIT`'s existing assertion extended).
