# 04 — Updated domain model

Extends `architecture/02-domain-model.md`. Phase 1 aggregates are unchanged; Phase 2 adds the following. Every table is
tenant-owned (`tenant_id`, RLS) and unit-scoped where relevant (`owner_org_unit_id` with ltree path checks).

```
                         Candidate ──(1:n)── Application ──► AdmissionCycle ──► ProgramOffering(program, intake)
                             │                    │                                   │
                             │ converts to        └─► ScreeningStageResult ◄──────────┤
                             ▼                                                        ▼
 Student (1.5) ◄──────── Offer ◄── MeritList ◄── ResultSet ◄── Attempt ◄── Allocation ◄── Session ◄── AssessmentEvent
                                                    ▲             │  ▲                                    │
 Academics component ◄──────── outcome ─────────────┤             │  └── Paper (sealed) ◄── AssessmentVersion ◄── Assessment
 Placement shortlist ◄──────── outcome ─────────────┘             ▼                              │
                                                       ResponseJournal / Response                Section ─► SelectionRule ─► ItemVersion ◄── Item
                                                                  │                                                           │
                                         ScoreLine ◄── EvaluationTask ◄── EvaluationPlan                                   ItemType (SPI)
                                             ▲              ▲
                                       AiSuggestion   ModerationAction
              IntegrityProfile ─► Signal / Snapshot ─► Flag ─► Incident ─► Decision (human)
```

## Aggregates and invariants

| Aggregate | Key fields | Invariants |
|---|---|---|
| **Candidate** | person data (encrypted fields as 1.5), verified contacts, identity documents, `student_id` once converted | One candidate per verified contact per tenant (dedup); personal data under `candidate.personal.read` |
| **AdmissionCycle** | year, unit, programs offered with intake and category quotas, dates, form schema version, eligibility rules, stages | Form schema is versioned and frozen once applications exist; rules are data (expression tree), evaluated server-side |
| **Application** | candidate, cycle, program preferences, answers (schema-validated JSON), documents, status pipeline | Status transitions only through the cycle's stage pipeline; every transition audited |
| **Item / ItemVersion** | type, stem, options/answer key (encrypted at rest), media, taxonomy, difficulty, marks, status (DRAFT → IN_REVIEW → APPROVED → RETIRED), provenance (human/AI/import) | Versions are immutable once APPROVED; papers reference versions, never items |
| **Assessment / AssessmentVersion** | purpose, sections, selection rules, marking scheme (positive/negative/partial), timing, navigation, attempt policy, integrity profile, evaluation plan | Publication validates that every rule can be satisfied from approved items; published versions immutable |
| **AssessmentEvent / Session** | assessment version, purpose context (admission cycle, subject offering, drive), sessions (fixed or window), venues, access-code policy | A session cannot start before its version is published and allocations exist |
| **Allocation** | candidate or student, session, seat/remote, accommodations (extra minutes, breaks), admit card | One allocation per person per event unless re-sit policy allows |
| **Attempt** | allocation, paper, state machine (SCHEDULED → ADMITTED → IN_PROGRESS ⇄ PAUSED → SUBMITTED / AUTO_SUBMITTED → EVALUATED → RESULT_FINAL), server deadline, resume count, device binding | Deadline computed server-side; state transitions idempotent; submission seals responses with a hash |
| **Paper** | attempt, ordered item versions, option shuffles, section layout, seed | Generated once, sealed (hash), identical on resume |
| **ResponseJournal / Response** | attempt, item, client sequence, payload, server time; latest response per item | Append-only journal; latest response derived; no write after seal |
| **EvaluationPlan / Task / ScoreLine** | auto/manual/AI per item, markers, tolerance, anonymisation; score lines with provenance (AUTO, MANUAL, MODERATED, AI_ACCEPTED) | Final score has exactly one effective line per response; every change keeps history and reason |
| **AiSuggestion** | response, model, template version, suggested score, rationale, confidence, reviewer action | Never effective by itself; only an evaluator's acceptance creates a ScoreLine |
| **ResultSet / Result** | event, computation policy (scaling, normalisation, tie-break), status (COMPUTED → APPROVED → PUBLISHED → REVISED), per-candidate totals, ranks, percentiles | Publication needs approval (four-eyes configurable); published results only revised by a new revision with reason |
| **MeritList / Offer** | cycle, program, category, ranked entries, cut-offs, offers with expiry and acceptance | Generated from a published ResultSet plus stage results; reproducible from inputs |
| **IntegrityProfile / Signal / Flag / Incident / Decision** | level, enabled detectors; signals with timestamps; flags with confidence and evidence refs; incidents with candidate response; human decision | Flags have no effect on scores; only a Decision does |
| **Drive / Shortlist / RecruiterAccess** | company, eligibility, assessment events, shortlist entries, consent records, recruiter scoped access | Recruiters see only consenting, shortlisted candidates of their drive |
| **ServiceIdentity** (KI-59) | envelope, custodian, roles | Effective permissions ⊆ envelope |

## Relationship to Phase 1

* Students (1.5) remain the record of enrolled learners; candidates become students through an audited conversion that
  reuses the 1.5 creation services (no parallel student record).
* Academic structure (1.4) supplies programs, courses, subjects and batches; assessments reference subject offerings for
  internal/semester purposes.
* Notifications, audit, outbox, field encryption, imports and reports are reused, not duplicated.
