# Runbook: exam delivery outages and candidate compensation

Increment 2.0 (C-3). Server time keeps running during an interruption; **nothing changes for candidates without an
approved compensation**. Policy defaults (how many extra minutes, when to offer a re-sit, who approves) are institution
decisions still open (Q-06); the screen only *suggests* "lost time + 5 minutes".

## Processes and detection

* Every API process writes a heartbeat every 10 s (`process_heartbeat`, platform table). A graceful shutdown removes its
  row, so deployments and scale-downs are not outages.
* The **outage detector** runs in the administrative process (one instance via lease, every 15 s). When no delivery
  instance has beaten for **45 s** (`IVOROVI_OUTAGE_THRESHOLD`) it opens a platform incident; organizations with open
  sessions get an incident entry and a notification ("Exam delivery interrupted"). When delivery beats again the incident
  is resolved, and each organization's entry lists the affected candidates (present or checked in during the window),
  with a notification to holders of `exam.incident.manage`.
* A crash of one delivery instance while others answer is not an outage (candidates reconnect through the load balancer).
* Institutions can also **report** an outage they observed (Examinations → Incidents → Report an outage).

## Compensation workflow

1. Exam controller (with `exam.incident.manage` **and** `exam.incident.decide`) opens the incident, checks the affected
   candidates and proposes: extend + new check-in (minutes), new check-in only, or no action (close). A rationale is
   required.
2. The proposal becomes an approval request (four-eyes, ADR-0007). The proposer cannot approve; a second person with
   `exam.incident.decide` (organization-wide) approves or rejects with a reason. Nothing is applied before that.
3. On approval every affected candidate receives a **bound entitlement** (single-use, only for that candidate, valid
   until the session end plus the extension, or 48 h for a new check-in) and an email without any code or link secret.
   The candidate uses it from the exam portal ("Use now").
4. Rejection or cancellation reopens the incident for a new proposal; expiry (2 days) closes the request without effect.
5. Audit trail: `exam.incident.opened/detected/reported`, `exam.compensation.proposed`, `approval.*`,
   `exam.compensation.applied`, `exam.check_in.compensation`.

## Operator checklist during an outage

1. Confirm in monitoring that the delivery processes are down (health endpoint, logs `platform.incident.opened`).
2. Restore service; do **not** change server time or session windows directly in the database.
3. After recovery, check that the incident shows an end time and the affected candidates.
4. Inform the institution; they decide compensation through the workflow above.

## Testing

`scripts/benchmark/delivery-failure-tests.sh` reproduces a crash with virtual candidates and verifies detection,
resolution and the affected-candidate list (results: `docs/performance/delivery-load-and-failure.md`).
