# Demo dataset: contract for every stream (D-18, approved 2026-11-16)

Owner: Session 3, which owns the runner, the shared registry, reset, snapshot, backup and the credential register. Each stream owns the step for its modules.

The aim is one demo institution, **Meridian Education Trust** (`meridian-demo`), whose records connect across all 18 modules. It is entirely synthetic, created through the real API, and can be reset in minutes.

## Rules

1. **Real API only.** A step uses the owning module's public REST endpoints as the personas in `spec.PERSONAS`. The only direct SQL is the organization skeleton, and only until add-on I's tenant-provisioning API exists; then it moves there.
2. **No numbers seeded as results.**
   - Attempts are taken through the delivery API with candidate tokens, so scoring, the journal and audit are genuine.
   - Analytics are computed by the platform from those attempts.
   - Descriptive answers are marked by evaluators through manual evaluation; scoring waits for them (C-1).
3. **Time is relative to the reset.**
   - Use `ctx.at(days=-14)`, `ctx.at(minutes=-10)` and so on, never calendar dates. Academic-year dates in step 10 are the one exception.
   - Anything that must be "now" (the live examination, its session codes, in-progress attempts) is created in `live(ctx)`, because a restored snapshot is older than the demonstration.
   - Timestamps are never shifted by SQL. The immutability guards forbid it, and it would not be genuine.
4. **Nothing missing is presented as done.**
   - A step that cannot run (no AI provider key, no code-execution host) calls `ctx.skip("reason")`.
   - The runner reports it as SKIPPED, skips the steps that need it, and the run is not OK.
5. **Synthetic people only.**
   - Names come from the persona and student lists; e-mails end in `@meridian.test`.
   - No real personal data, ever.
6. **No secrets in the repository.**
   - Locally, personas use `DEV_USER_PASSWORD` from `deploy/compose/.env`.
   - On the demo host, each persona has its own password in the credential register (`IVOROVI_DEMO_CREDENTIALS`, outside the repository).
   - A step never prints a key, token or password. Show-once keys are discarded, as in step 10.

## Steps

One file per stream block, in `scripts/demo/steps/`. They run in file-name order.

| Block | Owner | Modules |
|---|---|---|
| `10_*` | Session 2 (first version by S3) | Organization, personas, academic structure, students, security policy, branding (mandatory A, B, C, I; add-on I) |
| `20_*` | A | Question bank, assessments (mandatory D, E) |
| `30_*` | A | Schedules, enrolments, attempts, evaluation, results, admit cards, analytics (mandatory F, G, H) |
| `40_*` | Session 4 | AI drafts and suggestions, proctoring, sections, new question types (add-ons A, B, C) |
| `50_*` | Session 5 | Admission, learning analytics, placement (add-ons E, F, G) |
| `60_*` | Session 2 | Coding (add-on D) |
| `70_*` | Session 3 | SSO, LTI with Moodle, webhooks, API clients (add-on H) |

A step module defines:

```python
OWNER = "S5"                       # A, S2, S3, S4, S5
SUMMARY = "Admission cycle 2027 ..."
NEEDS = ["10_platform", "30_exams"]  # steps that must have succeeded first

def seed(ctx):   ...   # create the step's records (once, after a reset); return a short summary
def live(ctx):   ...   # optional: records relative to now (after seed and after every fast reset)
def verify(ctx): ...   # compare expected figures with the platform's (through the API); raise spec.StepError on a difference
```

`ctx` (`spec.Context`) provides:

| Member | Purpose |
|---|---|
| `ctx.t0`, `ctx.at(**delta)` | The reset time, and instants relative to it (ISO 8601 UTC) |
| `ctx.api(method, path, body, as_="admin", expect=(200, 201), headers=None)` | A call to the administrative API as a persona |
| `ctx.token(persona)` | A persona's access token (staff realm) |
| `ctx.put(kind, key, id)`, `ctx.get(kind, key)` | Cross-step lookups of server-generated ids. Examples: `ctx.get("batch", "CSE-2026-A")`, `ctx.get("subject", "CS201")`. `get` fails with a clear message when the providing step did not run |
| `ctx.skip(reason)` | Report the step as SKIPPED |
| `spec.idem()` | An `Idempotency-Key` header for endpoints that require one |

Step 10 records `program`, `course`, `batch`, `subject` (by code) and `staff` (by staff number). A step that adds personas adds one line each to `spec.PERSONAS`, in its own commit.

## Commands

```bash
scripts/demo-data.sh steps     # list steps in order with owner and dependencies
scripts/demo-data.sh rebuild   # reset + seed + live + verify (local: about 2 minutes for step 10)
scripts/demo-data.sh reset     # remove every Meridian record (Keycloak sign-ins kept)
scripts/demo-data.sh seed | live | verify
```

Requirements: the local stack is running (API on `localhost:8080`, Keycloak on `localhost:8180`). The runner refuses a non-local API unless `IVOROVI_DEMO_HOST=1` is set, which is done on the demo host only.

Run state (T0 and the lookups) lives in `$IVOROVI_DEMO_STATE`, by default `~/.ivorovi-demo/state.json`, never in the repository.

## Reset and backup (tiers approved 2026-11-16)

| Tier | What | When | Status |
|---|---|---|---|
| 1 `rebuild` | Full seed through the API, then verify | CI; producing the golden snapshot | **Available** (step 10) |
| 2 `snapshot` | After a verified rebuild: `pg_dump -Fc` of the whole database (the Keycloak schema included), the object-store bucket and checksums, tagged with T0 | Once per data change | Planned (Days 6–10) |
| 3 `reset` (fast, target ≤ 5 min) | Stop the APIs; restore the dump and the bucket; flush Valkey; purge RabbitMQ; start the APIs; run every step's `live()`; verify | Before each rehearsal and the demonstration | Planned (Days 6–10) |

Backup environment: a second host with the same stack. The golden snapshot and a daily dump are shipped to it, with restore drills on Days 10, 12 and 14.

Credential register: kept on the demo host only (`/etc/ivorovi-demo/credentials`, mode 600) and handed over through a channel agreed with the product owner. The repository holds only a template with names and purposes.
