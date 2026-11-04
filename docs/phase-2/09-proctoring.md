# 09 — Proctoring and examination integrity architecture

## Principles

1. **Flags are signals, not findings.** No automated disqualification, score change or accusation. Only a human
   Decision changes an outcome, and serious decisions are four-eyes.
2. **Proportionality.** Integrity level is chosen per assessment (none → basic signals → snapshots → AI-assisted review);
   the least intrusive level that meets the stakes is the default.
3. **Transparency and consent.** Candidates see, before starting, what is collected, why, for how long, and who reviews
   it; DPDP notice and consent are recorded; accommodations are respected (no flags for approved assistive technology).
4. **Fairness.** Detectors are evaluated for false-positive rates across lighting, skin tones, devices and disabilities
   before a tenant can enable them; thresholds are conservative; reviewers see confidence and context.

## Levels

| Level | Collected | Review |
|---|---|---|
| L0 None | attempt log only | — |
| L1 Basic | browser signals: focus loss, tab hidden, fullscreen exit, copy/paste attempts, multiple-screen hint (where the browser exposes it), IP changes, disconnections | rules produce low-severity flags |
| L2 Snapshots | L1 + webcam photo at start (identity) and at intervals/on events; optional screen snapshot where permitted | proctor reviews timeline |
| L3 AI-assisted | L2 + vision worker (face absent, multiple faces, face mismatch vs. start photo, phone-like object) producing flags with confidence | proctor reviews all AI flags; low-confidence flags hidden by default |
| Live (later) | WebRTC video to human proctors | out of scope for Phase 2 |

## Architecture

```
exam client ─► delivery API ─► signals (journal) ─┐
            └► snapshot upload (signed URL, object storage, encrypted) ─► queue ─► vision-worker (Python, separate
                                                                                  deployable, no DB credentials)
                                                                       ◄─ flags (confidence, evidence refs) ─┘
integrity module: flags ─► review queue (per session/proctor) ─► dismiss | escalate ─► Incident ─► candidate statement
                 ─► exam controller Decision (no action / warning / withhold result pending / void attempt / disqualify ✌)
```

* Vision models run on CPU (on-prem friendly); models and versions are recorded with each flag.
* Evidence is stored encrypted with short retention (default 90 days after results are final, longer only while an
  incident or appeal is open); access audited.
* Candidates can appeal a decision; appeals go to a different reviewer.
* Proctor dashboard: live roster (connected, last seen, flags count), admit/deny, extend time, pause session, message
  candidate, record incident.

## What we do not do

No keystroke biometrics, no emotion or “suspicion” scoring of faces, no continuous microphone recording in Phase 2, no
automated decisions, no sharing of proctoring evidence with recruiters.
