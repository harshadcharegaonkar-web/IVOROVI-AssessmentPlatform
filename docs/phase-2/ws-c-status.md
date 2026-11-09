# WS-C status / handover — M3c delivery hardening

Branch `ws-c/m3c-delivery-hardening` (based on origin/main `d1c7c4a`). Handover 2026-11-09, written when Session A
reassigned this session (message of 03:41 UTC). **Unfinished product work below returns to the primary session.**

## Done — follow-up A, KI-66 (layered rate limiting)

Check-in (`CandidateService.checkIn` + `CheckInLimits`), applied outside the database transaction, every refusal audited
once with its layer (`exam.session_code.rate_limited` / `refused`, `delayedMs` when delayed):

| Layer | Implementation |
|---|---|
| L1 candidate | 10 attempts/min (existing) + 30 failed/day (`ivorovi.exams.check-in.failed-per-candidate-per-day`) → 429 for that candidate; delivery calls 240/min keyed by organization + subject |
| L2 code | 5 failed/min (existing) + 20 failed/day; refusals now the uniform 409 `code_not_valid`; an existing code is **locked** (`session_code.locked_at`, audited `exam.session_code.locked`, status `LOCKED` in the staff code list; staff generate a new one) |
| L3 device | 30 failed/min per token `sid` (fallback subject) → 429 for that device |
| L4 portal | failed/min > max(30, 3 × 0.05 × expected candidates) → `platform_signal` CHECK_IN_ANOMALY + audit `exam.check_in.anomaly`; never blocks. Expected = `SessionRoster` hook or issued codes |
| L5 address | failed attempts of accounts **without** a check-in in an open session of the portal, > 300/min → progressive delay (250 ms per excess attempt, max 5 s), never refusal; ADDRESS_THROTTLED signal once per address and minute; **venue networks** per session (`PUT /api/v1/exam/sessions/{id}/venue-networks`, ≤ /16 or /48, audited) make it reporting-only |
| L6 organization | 120,000/min (existing) |

Also: delivery failed-auth per address no longer blocks (R-2), public portal/time lookups no longer capped at 60/address
(R-3), failed check-ins recorded per candidate/portal/minute (`check_in_failure`, for KI-73 impact).

Tests: `LayeredCheckInLimitsIT` (8: 500 and 1,000 candidates on one address, 1,000 + 20 attackers, venue allow-list,
distributed 200 × 5, device layer, portal lookups, failed authentication), `RateLimiterFailOpenIT` (Valkey stopped and
restarted), `DeliveryIT` updated to the new semantics (per-code → uniform 409; address → delay). Results:
`docs/performance/check-in-layered-limits.md`.

## Started — follow-up B, KI-71/72/73 (stopped here)

On the branch, compiled and covered only by the existing suites:
- migration `V202611132020__exams_delivery_health_incidents.sql`: heartbeat metrics + `process_heartbeat_sample`; platform
  incident severity/role/instances/clear_since/acknowledgement/mitigation, one-open-incident indexes, `platform_incident_event`
  timeline; `delivery_presence` serving instance, `delivery_presence_gap`; `check_in.instance_id`; impact `scope`,
  `instances`, `missing_check_ins`; candidate `reason`, `lost_seconds`; compensation action `EXTEND_BY_LOST_TIME`;
- heartbeat health reporting (R-5); `DeliveryTelemetry` records the serving instance and gaps > 30 s on presence/saves.

**Not done** (the schema above anticipates it — review or trim before merge):
1. Detector rules in `OutageDetector`: instance silent, error rate > 5 % for 2 min, p95 > 2 s for 2 min, dependency failing,
   healthy share < minimum; severities; correlation into one incident; recovery after 2 min clear (`ExamsProperties.Health`
   holds the thresholds; `IncidentWorkflowIT` will need the recovery hold step); processing of `platform_signal` into
   CHECK_IN_ANOMALY incidents; sample pruning.
2. Impact: instance-scoped affected candidates, `CHECK_IN_FAILED`, `EXPECTED_NOT_CHECKED_IN` (roster) and `missing_check_ins`,
   verified lost time from `delivery_presence_gap`.
3. Operator API + staff UI route (platform role only, Q-1), acknowledgement, notes, mitigation, post-incident report,
   runbook links.
4. Compensation `EXTEND_BY_LOST_TIME` (verified lost time + buffer) through the existing four-eyes approval.
5. Tests for each failure mode (follow-up B list).

## Test results (this handover)

See the commit message for the full backend suite result. Frontend: lint, typecheck, tests green (content 31, ui 11,
exam 8, web 39); API client regenerated (venue-network endpoints).

## Migrations

`V202611132010__exams_layered_check_in_limits.sql`, `V202611132020__exams_delivery_health_incidents.sql` (programme Day 1,
stream C). No existing migration edited.

## Open issues

KI-66: implemented, evidence above, **not** marked fixed (Session A after merge and CI); harness modes (Q-3) open.
KI-71, KI-72, KI-73: open, partially prepared as listed.
