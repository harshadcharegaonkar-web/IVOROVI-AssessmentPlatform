#!/usr/bin/env python3
"""Demo dataset runner (D-18; contract: docs/demo-programme/demo-data.md). Synthetic data only.

  steps     list the steps in run order with owner and dependencies
  rebuild   reset, then seed and verify every step (the full build; also produces the input for the golden snapshot)
  reset     remove every record of the demo institution (Keycloak sign-ins are kept)
  seed      run every step's seed() in order
  live      run every step's live() (records relative to "now": today's live examination, codes, attempts)
  verify    compare every step's expected figures with the platform's, through the API

Each step is scripts/demo/steps/NN_<name>.py, owned by one stream (number blocks in the contract). A step that cannot run
here raises spec.Skipped; the run reports it as SKIPPED with the reason and verify fails for it, so nothing missing is
presented as done. Run state (T0 and server-generated ids) is kept in $IVOROVI_DEMO_STATE (default
~/.ivorovi-demo/state.json), never in the repository.
"""
import datetime as dt
import importlib.util
import os
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import spec  # noqa: E402

STATE = os.environ.get("IVOROVI_DEMO_STATE", os.path.expanduser("~/.ivorovi-demo/state.json"))


def load_steps():
    steps = []
    d = os.path.join(HERE, "steps")
    for name in sorted(os.listdir(d)):
        if not (name.endswith(".py") and name[:2].isdigit()):
            continue
        s = importlib.util.spec_from_file_location("demo_step_" + name[:-3], os.path.join(d, name))
        m = importlib.util.module_from_spec(s)
        s.loader.exec_module(m)
        m.ID = name[:-3]
        for attr in ("OWNER", "SUMMARY", "seed", "verify"):
            if not hasattr(m, attr):
                sys.exit(f"step {name} has no {attr} (see docs/demo-programme/demo-data.md)")
        m.NEEDS = getattr(m, "NEEDS", [])
        steps.append(m)
    ids = [m.ID for m in steps]
    for m in steps:
        for n in m.NEEDS:
            if n not in ids or ids.index(n) > ids.index(m.ID):
                sys.exit(f"step {m.ID} needs {n}, which does not exist or does not run before it")
    return steps


def guard():
    status, _ = spec.http("GET", f"{spec.API}/actuator/health")
    if status != 200:
        sys.exit("API is not running on " + spec.API)
    local = spec.API.startswith(("http://localhost", "http://127.0.0.1"))
    if not local and os.environ.get("IVOROVI_DEMO_HOST") != "1":
        sys.exit("refusing to run against a non-local API (set IVOROVI_DEMO_HOST=1 only on the demo host)")


def context(fresh):
    if fresh and os.path.exists(STATE):
        os.remove(STATE)
    ctx = spec.Context(dt.datetime.now(dt.timezone.utc).replace(microsecond=0), STATE)
    ctx.save()
    return ctx


def run(phase, steps, ctx, before=None):
    """Runs one phase over all steps; returns {step: (status, detail)}. A failed or skipped step skips its dependants, and
    a step whose earlier phase (``before``: the seed results) did not succeed is skipped in this phase too."""
    results = {}
    for m in steps:
        blocked = [n for n in m.NEEDS if results.get(n, ("OK",))[0] != "OK"]
        if before is not None and before.get(m.ID, ("OK",))[0] != "OK":
            results[m.ID] = ("SKIPPED", "seed " + before[m.ID][0].lower())
        elif blocked:
            results[m.ID] = ("SKIPPED", "needs " + ", ".join(blocked))
        elif not hasattr(m, phase):
            results[m.ID] = ("OK", f"no {phase}()")
        else:
            try:
                detail = getattr(m, phase)(ctx)
                results[m.ID] = ("OK", detail or "")
            except spec.Skipped as e:
                results[m.ID] = ("SKIPPED", str(e))
            except spec.StepError as e:
                results[m.ID] = ("FAILED", str(e))
            except Exception as e:  # a bug in a step: show where, keep going with the independent steps
                results[m.ID] = ("FAILED", f"{type(e).__name__}: {e}\n{traceback.format_exc(limit=3)}")
        status, detail = results[m.ID]
        print(f"  {status:8} {phase:6} {m.ID:28} {m.OWNER:4} {str(detail).splitlines()[0] if detail else ''}")
        if status == "FAILED" and "\n" in str(detail):
            print("           " + "\n           ".join(str(detail).splitlines()[1:]))
    return results


def report(results, phase):
    bad = {k: v for k, v in results.items() if v[0] != "OK"}
    if bad:
        print(f"\n{phase}: {len(results) - len(bad)} of {len(results)} steps OK; not OK: " + ", ".join(f"{k} ({v[0]})" for k, v in bad.items()))
        return 1
    print(f"\n{phase}: all {len(results)} steps OK")
    return 0


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    steps = load_steps()
    if cmd == "steps":
        for m in steps:
            print(f"{m.ID:28} {m.OWNER:4} needs [{', '.join(m.NEEDS)}]  {m.SUMMARY}")
        return 0
    if cmd not in ("rebuild", "reset", "seed", "live", "verify"):
        sys.exit(__doc__)
    guard()
    if cmd in ("reset", "rebuild"):
        import reset as resetter
        resetter.reset_institution()
        if cmd == "reset":
            return 0
    ctx = context(fresh=cmd in ("rebuild", "seed"))
    if cmd == "verify":
        return report(run("verify", steps, ctx), "verify")
    if cmd == "live":
        return report(run("live", steps, ctx), "live")
    seeded = run("seed", steps, ctx)
    live = run("live", steps, ctx, before=seeded)
    verified = run("verify", steps, ctx, before=seeded)
    return max(report(seeded, "seed"), report(live, "live"), report(verified, "verify"))


if __name__ == "__main__":
    sys.exit(main())
