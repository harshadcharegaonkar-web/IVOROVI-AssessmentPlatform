# 05 — Assessment engine architecture

## 1. One engine, many purposes

The engine is the pipeline **item bank → assessment definition → schedule → delivery → evaluation → results**. Business
workflows differ only in *configuration presets* and *outcome consumers*:

| Purpose | Preset defaults (all overridable within policy) | Outcome consumer |
|---|---|---|
| ADMISSION | fixed sessions, shift normalisation optional, integrity basic+snapshots, objective-heavy, four-eyes publication | `admissions` → merit lists, offers |
| INTERNAL | window sessions, no proctoring by default, mixed item types | `academics` → component marks |
| SEMESTER | fixed sessions, double marking for essays, moderation, four-eyes | `academics` → grades |
| APTITUDE | adaptive-ready layout (later), per-section timing, percentile reporting | `placement` or `admissions` |
| TECHNICAL | mixed objective + coding | `placement` |
| CODING | coding items, code-runner, plagiarism similarity check | `placement` / `academics` |
| PLACEMENT | drive-scoped allocation, recruiter-shareable scorecard | `placement` → shortlists |

Adding a purpose = adding a preset row and (if needed) a consumer; **no new engine code paths**. A purpose never changes
how timing, autosave, scoring, evaluation or result publication work.

## 2. Extension points (SPIs)

| SPI | Implemented by | Contract |
|---|---|---|
| `ItemTypeHandler` | each item type (choice, numeric, text, essay, upload, matching, ordering, blank, coding) | validate authoring payload; produce candidate render model **without answer key**; validate a response; auto-score (or `NEEDS_MANUAL`/`ASYNC`) with partial-credit rules; rubric template |
| `SelectionStrategy` | fixed list, random-by-tags, random-by-difficulty band, stratified | deterministic given a seed; validated at publication |
| `ScoringPolicy` | marking scheme (positive, negative, partial, cap) | pure function over score lines |
| `ResultPolicy` | raw total, section totals, scaling, normalisation across sessions, percentile, tie-break chain | pure, reproducible; versioned |
| `OutcomeConsumer` | admissions, academics, placement | receives `ResultPublished` event with result-set id; reads through `results` API; never reads engine tables |
| `IntegrityDetector` | browser signals, snapshot analysis (vision), similarity | emits flags only |
| `EvaluationAssistant` | AI gateway | returns suggestions only |

## 3. Lifecycle and states

```
Item:        DRAFT ─► IN_REVIEW ─► APPROVED ─► RETIRED          (new version for any change after approval)
Assessment:  DRAFT ─► IN_REVIEW ─► PUBLISHED(vN) ─► ARCHIVED     (publication freezes an AssessmentVersion)
Event:       PLANNED ─► SCHEDULED ─► LIVE ─► CLOSED ─► EVALUATING ─► RESULTS_APPROVED ─► PUBLISHED
Attempt:     SCHEDULED ─► ADMITTED ─► IN_PROGRESS ⇄ PAUSED ─► SUBMITTED | AUTO_SUBMITTED ─► EVALUATED ─► FINAL
Result set:  COMPUTED ─► APPROVED ─► PUBLISHED ─► REVISED(n)
```

## 4. Engine invariants

1. Papers reference immutable item versions; editing an item never changes a delivered paper.
2. Answer keys and scoring data never leave the server during delivery.
3. Time is server-authoritative; the client only displays it.
4. Every answer the server acknowledged is durable before the acknowledgement.
5. Scores are derived data with provenance; recomputation from responses + policies reproduces them exactly.
6. Consumers depend on published result sets, never on in-progress data.
7. AI and integrity detectors produce suggestions and flags; only humans change outcomes.

## 5. Events (outbox, catalogue for webhooks/notifications)

`assessment.published`, `exam.event.scheduled`, `exam.session.started/ended`, `exam.attempt.submitted` (internal only),
`exam.incident.recorded/decided`, `evaluation.completed`, `result.approved`, `result.published`, `result.revised`,
`admission.application.submitted`, `admission.merit_list.published`, `admission.offer.issued/accepted`,
`placement.shortlist.released`. Webhook payloads follow the 1.7 minimisation rules (identifiers, no personal data, no
scores unless the endpoint is approved for result data — decision P2-12).
