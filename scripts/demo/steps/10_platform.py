"""Step 10 (S2 owns the modules; first version by S3 from the existing generator): organization skeleton, personas,
academic structure, staff, students, imports and the first integrations. Wraps scripts/demo/demo_data.py (increments
1.0–1.7), whose data specification and expected figures stay where they are, and records the ids later steps need.
"""
import spec

OWNER = "S2"
SUMMARY = "Organization, personas, academic structure, staff, 20 + imported students, integrations (1.0-1.7)"
NEEDS = []


def _exit_to_error(fn):
    try:
        fn()
    except SystemExit as e:  # the generator reports problems with sys.exit(message)
        if e.code not in (None, 0):
            raise spec.StepError(str(e.code))


def seed(ctx):
    import demo_data  # needs the local .env; imported only when the step runs
    _exit_to_error(demo_data.seed)
    for kind, path in (("program", "/api/v1/academics/programs"), ("course", "/api/v1/academics/courses"),
                       ("batch", "/api/v1/academics/batches"), ("staff", "/api/v1/staff")):
        for item in ctx.api("GET", path + "?size=200")["items"]:
            ctx.put(kind, item.get("code") or item.get("staffNo"), item["id"])
    for item in ctx.api("GET", "/api/v1/academics/subjects?size=200")["items"]:
        s = item.get("subject", item)
        ctx.put("subject", s["code"], s["id"])
    return f"{len(ctx.store.get('batch', {}))} batches, {len(ctx.store.get('subject', {}))} subjects recorded"


def verify(ctx):
    import demo_data
    _exit_to_error(demo_data.verify)
    return "dashboard figures match the specification"
