# 07 — Examination delivery architecture

## 1. Components

```
 Candidate browser (apps/exam, small bundle, works on 2 Mbps)            Staff browser (apps/web)
   │  HTTPS (JSON) + optional WebSocket for control messages                 │
   ▼                                                                         ▼
 Delivery API (process profile "delivery", horizontally scaled) ◄── shared ──► Main API
   │ Valkey: attempt hot state (deadline, seq, last-seen)                     │
   │ PostgreSQL: attempts, papers, response journal (partitioned by event)    │
   │ Outbox → RabbitMQ: submitted → evaluation; signals → integrity            │
   └──► code-runner (coding), vision-worker (snapshots) — separate deployables
```

The candidate client is a separate, minimal SPA (no admin code, no admin routes), built for resilience: it keeps a
local queue in IndexedDB and never needs the main application to be reachable.

## 2. Server-authoritative timing

* At start the server computes `deadline = start + duration + accommodation + approved extensions` and stores it with the
  attempt; section deadlines likewise. The client receives `serverNow` and `deadline` and displays a countdown corrected
  for clock offset on every response.
* Writes after `deadline + grace` (grace = network allowance, default 30 s) are rejected; a scheduled sweeper
  auto-submits attempts whose deadline passed (idempotent).
* Pauses (only by a proctor/controller, e.g. power failure at a centre) extend the deadline by the paused interval and are
  audited. Candidates cannot pause.
* Window sessions: the attempt deadline is also capped by the window end unless policy says otherwise.

## 3. Reliable autosave

* Every answer change is a **response event** `{attemptId, itemId, clientSeq, payload, clientTime}` sent with an
  idempotency key = `attemptId:clientSeq`.
* The server appends to the journal (unique `(attempt, client_seq)`), updates the latest response, and acknowledges the
  highest contiguous sequence. Duplicate sends are acknowledged without effect; out-of-order sends are stored and applied
  by sequence.
* The client keeps unacknowledged events in IndexedDB and retries with backoff; on reload it replays them. A visible
  status (“Saved 3 s ago” / “Saving…” / “Offline — answers kept on this device”) is always shown.
* Batching: the client coalesces rapid changes to one event per item per second; the server accepts batches.

## 4. Network interruption and recovery

* Heartbeats every 15 s keep `lastSeen`; proctors see disconnected candidates in real time.
* Reconnection on the same device resumes immediately: the server returns the sealed paper, latest responses and the
  current deadline; the client reconciles its local queue by sequence numbers.
* Resume on another device requires proctor approval (device binding, see §6); the old device's session is revoked.
* Time keeps running during disconnection by default (fairness for fixed sessions); policies may allow a capped
  “disconnection allowance” approved by a proctor (audited).

## 5. Idempotent submission

* `POST /delivery/attempts/{id}/submit` with an idempotency key and the client's last sequence number. The server waits
  (bounded) for missing sequences, seals the response set (SHA-256 over ordered latest responses), moves the attempt to
  SUBMITTED and returns a receipt (attempt id, seal hash, time) the candidate can keep.
* Repeated submits return the same receipt. Auto-submit uses the same path. After sealing, writes are rejected (409).

## 6. Candidate identity and examination access

| Control | Mechanism |
|---|---|
| Authentication | candidate account (Keycloak, on-demand per D-P1-03) or event-scoped **access code + date of birth/application number** for one-off admission tests (decision P2-05) |
| Allocation check | the candidate must hold an allocation for this session; window/time checks server-side |
| Session access code | proctor-released code per session or room (optional) |
| Identity verification | photo capture compared by a proctor with the application photo; optional AI face-match **suggestion** (never automatic rejection); centre mode: invigilator confirms ID document |
| Device binding | first device fingerprint (non-invasive: random device key in storage + user agent) bound to the attempt; another device needs proctor approval |
| Network restrictions | optional IP allow-list per centre |
| Browser controls | fullscreen request, copy/paste and context-menu blocking (deterrence, logged as signals; not security boundaries) |

## 7. Incident recording

Proctors and the system record incidents (identity mismatch, disconnection, suspected malpractice, technical failure)
with timestamps, evidence references and actor; candidates can add a statement; decisions belong to the exam controller
(`09-proctoring.md`).

## 8. Secure result processing

Scoring runs server-side after sealing; answer keys are decrypted only inside the scoring context; score changes require
reasons and keep history; result computation is a pure function of sealed responses + versioned policies (reproducible
for appeals); publication requires approval (four-eyes configurable) and is audited; candidates see results only after
publication.

## 9. Coding assessments

Untrusted code **never runs in the API or delivery processes**. The delivery API queues runs to `code-runner`
(ADR-0004: Kata Containers preferred, gVisor fallback; no network, CPU/memory/pid/time limits, read-only images,
ephemeral tmpfs, outside-enforced wall clock). The ADR-0004 proof of concept must pass before any coding assessment is
offered (blocking dependency). Candidate “run against samples” is rate-limited per attempt; hidden tests run only on
final submission.

## 10. Capacity targets and verification

2,000 concurrent candidates per event in the reference environment, 10,000 per SaaS region: p95 save < 300 ms, zero
lost acknowledged answers under chaos tests (kill delivery pods, drop network, Valkey restart), submission burst of
10,000 in 2 minutes. Load tests with scripted candidates are an acceptance gate for every delivery increment.
