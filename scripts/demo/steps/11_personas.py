"""Step 11 (S2 owns identity; first version by S3): the examination personas, through the real user and role workflow.

* One bootstrap holder: the organization's first exam controller is written directly, like the organization skeleton. A
  privileged role can only be approved by someone who already holds its privileged permissions (P2-08), so the first holder
  of an organization must come from provisioning. This moves to add-on I's tenant provisioning (S2) when it exists.
* Everyone else as an administrator would do it: user created with an invitation, the invitation accepted from the e-mail
  (Mailpit locally), the role assigned; a privileged role waits for the four-eyes approval, which the controller gives.
"""
import json
import re
import time
import urllib.parse
import urllib.request

import spec

OWNER = "S2"
SUMMARY = "Exam personas: bootstrap controller; second controller, designer and invigilator via invitation and four-eyes approval"
NEEDS = ["10_platform"]

MAILPIT = spec.ENV.get("MAILPIT_URL", "http://localhost:8025")
MIE = spec.U(0xd101)
BOOTSTRAP = "controller"
INVITED = ["controller2", "designer", "proctor"]   # created through the API, in this order

def _bootstrap(ctx):
    import demo_data  # the skeleton's helpers (local .env, docker compose exec)
    email, given, family, role, unit = spec.PERSONAS[BOOTSTRAP]
    if demo_data.psql(f"SELECT count(*) FROM user_account WHERE tenant_id = '{spec.TENANT}' AND email = '{email}'") != "0":
        return
    subject = demo_data.keycloak_user(demo_data.provisioner_token(), email, given, family)
    uid, role_id = spec.U(0xd1100), demo_data.psql(f"SELECT id FROM role WHERE code = '{role}' AND tenant_id IS NULL")
    demo_data.psql("; ".join([
        f"INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name, family_name, status) VALUES "
        f"('{uid}', '{spec.TENANT}', '{subject}', '{email}', '{given} {family}', '{given}', '{family}', 'ACTIVE')",
        f"INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary) VALUES ('{spec.U(0xd1200)}', '{spec.TENANT}', '{uid}', '{unit}', true)",
        f"INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES "
        f"('{spec.U(0xd1300)}', '{spec.TENANT}', '{uid}', '{role_id}', 'ORG_UNIT', '{unit}')"]))


def _invitation_token(email, after):
    """The invitation token from the newest e-mail to this address (the token is in the link fragment)."""
    for _ in range(40):
        q = urllib.parse.quote(f"to:{email}")
        with urllib.request.urlopen(f"{MAILPIT}/api/v1/search?query={q}&limit=5") as r:
            msgs = [m for m in json.load(r).get("messages", []) if m.get("Created", "") >= after]
        for m in msgs:
            with urllib.request.urlopen(f"{MAILPIT}/api/v1/message/{m['ID']}") as r:
                text = json.load(r).get("Text", "")
            found = re.search(r"#token=([A-Za-z0-9_\-.~%]+)", text)
            if found:
                return urllib.parse.unquote(found.group(1))
        time.sleep(0.5)
    raise spec.StepError(f"no invitation e-mail for {email} in Mailpit")


def _invite(ctx, key, roles):
    email, given, family, role, unit = spec.PERSONAS[key]
    existing = [u for u in ctx.api("GET", "/api/v1/users?size=100&q=" + urllib.parse.quote(email))["items"] if u.get("email") == email]
    if existing:
        return existing[0]["id"]
    after = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(time.time() - 5))
    created = ctx.api("POST", "/api/v1/users", {"email": email, "givenName": given, "familyName": family, "orgUnitId": unit,
                                                "sendInvitation": True}, headers=spec.idem())
    user = created["user"]["user"]["id"]
    token = _invitation_token(email, after)
    spec.ok(spec.http("POST", spec.API + "/api/v1/public/invitations/accept", {"token": token, "password": spec.persona_password(key)}), 204)
    assigned = spec.http("POST", f"{spec.API}/api/v1/users/{user}/role-assignments", {"roleId": roles[role], "orgUnitId": unit}, token=ctx.token("admin"))
    body = spec.ok(assigned, 201, 202)
    if assigned[0] == 202:   # privileged (P2-08): a holder of the role's privileged permissions decides, never the requester
        approval = body["approvalRequestId"]
        ctx.api("POST", f"/api/v1/approvals/{approval}/decision", {"approve": True, "reason": "Appointed by the examination board (demo)"}, as_=BOOTSTRAP)
        ctx.put("approval", f"role:{key}", approval)
    return user


def seed(ctx):
    _bootstrap(ctx)
    raw = ctx.api("GET", "/api/v1/roles?size=200")
    roles = {r["code"]: r["id"] for r in (raw["items"] if isinstance(raw, dict) else raw)}
    for key in INVITED:
        ctx.put("user", key, _invite(ctx, key, roles))
    return "bootstrap controller + " + ", ".join(INVITED)


def verify(ctx):
    for key in [BOOTSTRAP] + INVITED:
        email, _, _, role, unit = spec.PERSONAS[key]
        me = ctx.api("GET", "/api/v1/me", as_=key)
        held = json.dumps(me)
        if role not in held:
            raise spec.StepError(f"{key} ({email}) does not hold {role}: {held[:200]}")
    return "every persona signs in and holds its role"
