"""Step 30 (A owns the examination; first version by S3): the DS mid-term delivered for real.

* A candidate portal, and candidates as real candidate-realm accounts (the students of CSE-2025-A who take Data Structures).
* "DS mid-term, sitting 1": every candidate checks in with a single-use code, takes the paper through the delivery API
  with their own token, and submits; scoring is the platform's. Results are reviewed by the second controller, and
  their publication is approved by the first (independent approval, R-1). Candidates can then see their result.
* "DS mid-term, sitting 2": taken and submitted; the review waits for approval (the approver's queue in the demo).
* live(): "DS mid-term, today's sitting" open now, with two candidates part-way through. live() runs after every reset,
  so this sitting is always live at the demonstration.
No timestamps are shifted: sittings 1 and 2 are as old as the snapshot they are restored from (demo-data.md, rule 3).
Descriptive questions and manual evaluation (/api/v1/evaluation/*) follow when they are on main (C-1, C-2).
"""
import importlib.util
import os
import time
import urllib.parse

import spec

OWNER = "A"
SUMMARY = "Portal, candidates, DS mid-term sittings: published results, results awaiting approval, and a live sitting"
NEEDS = ["10_platform", "11_personas", "20_questions"]

CSE = spec.U(0xd121)
CAND_REALM = spec.KC + "/realms/ivorovi-candidates"
PORTAL = "meridian-exams"
# candidate personas: (key, given, family, accuracy) - students of CSE-2025-A (step 10); synthetic
CANDIDATES = [("aarav", "Aarav", "Kapoor", 0.92), ("ishita", "Ishita", "Menon", 0.75), ("kabir", "Kabir", "Sethi", 0.58),
              ("ananya", "Ananya", "Iyer", 0.83), ("reyansh", "Reyansh", "Gupta", 0.67)]
SITTING_1, SITTING_2 = CANDIDATES[:3], CANDIDATES[3:]
LIVE = CANDIDATES[:2]


def _questions():
    path = os.path.join(os.path.dirname(__file__), "20_questions.py")
    s = importlib.util.spec_from_file_location("demo_step_20_for_30", path)
    m = importlib.util.module_from_spec(s)
    s.loader.exec_module(m)
    return {q["content"]["blocks"][0]["runs"][0]["text"]: q for q in m.QUESTIONS}


def email(key):
    return f"{key}.cand@students.meridian.test"


# ------------------------------------------------------------------------------------------------ candidate accounts
def _provisioner():
    return spec.ok(spec.http("POST", CAND_REALM + "/protocol/openid-connect/token", form={
        "grant_type": "client_credentials", "client_id": "ivorovi-candidate-provisioner", "client_secret": spec.ENV["KEYCLOAK_PROVISIONER_SECRET"]}), 200)["access_token"]


def _ensure_candidate(admin_token, key, given, family):
    base = spec.KC + "/admin/realms/ivorovi-candidates/users"
    found = spec.ok(spec.http("GET", f"{base}?email={urllib.parse.quote(email(key))}&exact=true", token=admin_token), 200)
    if not found:
        spec.ok(spec.http("POST", base, {"username": email(key), "email": email(key), "emailVerified": True, "enabled": True, "firstName": given,
                                         "lastName": family, "credentials": [{"type": "password", "value": _password(key), "temporary": False}]},
                          token=admin_token), 201)


def _password(key):
    register = os.environ.get("IVOROVI_DEMO_CREDENTIALS")
    if register:
        import json
        with open(register) as f:
            return json.load(f)[email(key)]
    return spec.ENV["DEV_USER_PASSWORD"]


def _candidate_token(key):
    return spec.ok(spec.http("POST", CAND_REALM + "/protocol/openid-connect/token", form={
        "grant_type": "password", "client_id": "ivorovi-candidate-dev-cli", "scope": "openid", "username": email(key),
        "password": _password(key)}), 200)["access_token"]


def _delivery(method, path, token, body=None, expect=(200,)):
    return spec.ok(spec.http(method, f"{spec.DELIVERY}/api/v1/delivery/portals/{PORTAL}{path}", body, token=token), *expect)


# ------------------------------------------------------------------------------------------------ sittings
def _sitting(ctx, name, opens, closes, candidates):
    sid = ctx.api("POST", "/api/v1/exam/schedules", {"versionId": ctx.get("assessment_version", "DS-MID-2026"), "portalId": ctx.get("portal", PORTAL),
                                                    "orgUnitId": CSE, "opensAt": opens, "closesAt": closes, "title": name}, as_="controller")["id"]
    csv = "".join(f"{email(k)},{g} {f}\n" for k, g, f, _ in candidates)
    ctx.api("POST", f"/api/v1/exam/schedules/{sid}/enrolments/upload", {"csv": csv, "apply": True}, as_="controller")
    codes = [c["code"] for c in ctx.api("POST", f"/api/v1/exam/schedules/{sid}/codes", {"count": len(candidates)}, as_="controller")]
    return sid, codes


def _take(sid, code, key, accuracy, stems, answer_share=1.0, submit=True):
    token = _candidate_token(key)
    _delivery("GET", "/me", token)
    _delivery("POST", "/check-ins", token, {"code": code, "clockOffsetMs": 0, "systemCheck": {}})
    attempt = _delivery("POST", f"/exams/{sid}/attempt", token, {})
    aid = attempt["id"]
    view = _delivery("GET", f"/attempts/{aid}", token)
    items = view.get("items") or view.get("questions") or []
    answered = 0
    for n, item in enumerate(items):
        if n >= round(len(items) * answer_share):
            break
        stem = item["content"]["blocks"][0]["runs"][0]["text"]
        q = stems[stem]
        right = (n * 37 % 100) / 100 < accuracy          # deterministic, varies per candidate by accuracy
        if q["type"] == "SINGLE_CHOICE":
            options = [o["id"] for o in q["body"]["options"]]
            value = q["answerKey"]["correct"] if right else next(o for o in options if o != q["answerKey"]["correct"])
        else:
            value = q["answerKey"]["value"] if right else str(int(q["answerKey"]["value"]) + 1)
        _delivery("PUT", f"/attempts/{aid}/answers/{item['position']}", token, {"seq": 1, "response": {"value": value}})
        answered += 1
    if submit:
        _delivery("POST", f"/attempts/{aid}/submit", token, {"submitKey": f"demo-{aid}"})
    return aid, answered


def _wait_scored(ctx, sid, n):
    for _ in range(60):
        r = ctx.api("GET", f"/api/v1/exam/results/{sid}", as_="controller2")
        if r["provisional"] + r["released"] >= n:
            return r
        time.sleep(1)
    raise spec.StepError(f"results of {sid} not scored within a minute")


def seed(ctx):
    portal = ctx.api("POST", "/api/v1/exam/portals", {"portalKey": PORTAL, "name": "Meridian examinations", "mfaPolicy": "OFF",
                                                      "selfRegistration": True})
    ctx.put("portal", PORTAL, portal["id"])
    admin_token = _provisioner()
    for key, given, family, _ in CANDIDATES:
        _ensure_candidate(admin_token, key, given, family)
    stems = _questions()
    for label, group, publish in (("DS mid-term, sitting 1", SITTING_1, True), ("DS mid-term, sitting 2", SITTING_2, False)):
        sid, codes = _sitting(ctx, label, ctx.at(minutes=-1), ctx.at(hours=3), group)
        ctx.put("schedule", label, sid)
        for (key, _, _, accuracy), code in zip(group, codes):
            _take(sid, code, key, accuracy, stems)
        _wait_scored(ctx, sid, len(group))
        ctx.api("POST", f"/api/v1/exam/results/{sid}/reviews", {"comment": "Checked against the answer key"}, as_="controller2")
        if publish:
            approval = _approval_for(ctx, sid)
            ctx.api("POST", f"/api/v1/approvals/{approval}/decision", {"approve": True, "reason": "Results verified"}, as_="controller")
    return "portal, 5 candidates, sitting 1 published, sitting 2 awaiting approval"


def _approval_for(ctx, sid):
    r = ctx.api("GET", f"/api/v1/exam/results/{sid}", as_="controller")
    approval = next((v["approvalId"] for v in r["reviews"] if v.get("approvalId") and v.get("decidedAt") is None), None)
    if not approval:
        raise spec.StepError("no pending approval for the results of " + sid + ": " + str(r)[:300])
    return approval


def live(ctx):
    stems = _questions()
    sid, codes = _sitting(ctx, "DS mid-term, today's sitting", ctx.at(minutes=-10), ctx.at(hours=3), LIVE)
    ctx.put("schedule", "live", sid)
    for (key, _, _, accuracy), code in zip(LIVE, codes):
        _take(sid, code, key, accuracy, stems, answer_share=0.5, submit=False)
    return "today's sitting open with 2 candidates part-way through"


def verify(ctx):
    published = ctx.api("GET", f"/api/v1/exam/results/{ctx.get('schedule', 'DS mid-term, sitting 1')}", as_="controller")
    pending = ctx.api("GET", f"/api/v1/exam/results/{ctx.get('schedule', 'DS mid-term, sitting 2')}", as_="controller")
    rows1, rows2 = published["rows"], pending["rows"]
    if published["released"] != len(SITTING_1):
        raise spec.StepError(f"sitting 1: {published['released']} of {len(SITTING_1)} results released: " + str([x.get("scoreStatus") for x in rows1]))
    if pending["released"] != 0 or pending["provisional"] != len(SITTING_2) or not any(v.get("decidedAt") is None for v in pending["reviews"]):
        raise spec.StepError("sitting 2 should be reviewed and awaiting approval: " + str(pending)[:300])
    seen = _delivery("GET", f"/attempts/{rows1[0].get('attemptId')}/result", _candidate_token(SITTING_1[0][0])) if rows1 and rows1[0].get("attemptId") else None
    return f"sitting 1: {len(rows1)} published; sitting 2: {len(rows2)} awaiting approval" + ("; a candidate sees the result" if seen else "")
