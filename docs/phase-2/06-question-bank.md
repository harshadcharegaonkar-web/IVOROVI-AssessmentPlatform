# 06 — Question bank architecture

## Structure

* **Banks** belong to a unit (organization, university, college, department) and inherit visibility down the
  hierarchy; sharing upward or sideways is an explicit, audited grant.
* **Taxonomy**: subject (from 1.4), topic tree, learning outcome, Bloom level, difficulty (designer estimate, later
  calibrated from item statistics), language, tags.
* **Items** have immutable **versions**. A version holds: type, stem (sanitised rich text + LaTeX), media references,
  options, answer key/scoring data (field-encrypted, `itembank.answer_key` AAD), marks, rubric, explanation, metadata.

## Workflow and confidentiality

| Stage | Who | Controls |
|---|---|---|
| Draft | author (faculty/designer/AI draft) | author-only visibility by default |
| Review | reviewer ≠ author | checklist (correctness, ambiguity, bias, accessibility, copyright); comments; decision audited |
| Approved | selectable in assessments | immutable; usage tracked |
| Retired | not selectable | kept for papers that used it |

* **Secure banks** (for high-stakes papers): items are visible only to named custodians; answer keys decrypted only
  in the scoring service context; viewing a secure item is audited; papers can be assembled without revealing items to
  the scheduler (“blind assembly” — the controller sees counts and statistics, not content).
* **Exposure control**: per-item exposure count and last-used date; selection rules can exclude items used in the last
  N events.

## Import and export

CSV (objective types) using the 1.6 tabular engine (validation report, dry run, idempotency); QTI 3.0 subset (choice,
text entry, extended text, match, order); Word import is out of scope. Media through object storage with malware
scanning (RB-1 applies). Export only by bank custodians, audited.

## Quality

* Duplicate/similar item detection (normalised text shingles; optional embedding similarity through the AI gateway).
* Post-exam item analysis: facility (p-value), discrimination (point-biserial), distractor analysis, flagged items for
  review; designers can void an item for an event (rescore everyone, audited).
* AI-generated drafts carry provenance (model, template version) and always require human review.

## Media and math

Images/audio/video in object storage, served through short-lived signed URLs bound to the attempt during delivery;
KaTeX rendering client-side; alt text required for images in published items (accessibility gate at review).
