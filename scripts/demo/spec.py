"""Shared registry of the demo dataset (D-18; contract in docs/demo-programme/demo-data.md).

Every step imports this module for the demo institution, the personas, the reset time T0, the API helpers and the
cross-step lookup store. Synthetic data only. Nothing here holds a secret: passwords come from deploy/compose/.env
locally, or from the credential register on the demo host (never from the repository).
"""
import datetime as _dt
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
API = os.environ.get("API_URL", "http://localhost:8080")
DELIVERY = os.environ.get("DELIVERY_URL", "http://localhost:8090")
KC = os.environ.get("KEYCLOAK_URL", "http://localhost:8180")

# the single demo institution (existing since increment 1.7; kept so earlier screenshots and runbooks stay valid)
TENANT = "0192d000-0000-7000-8000-00000000000d"
TENANT_CODE = "meridian-demo"


def U(n):
    """Deterministic ids of records the dataset inserts with a known id (the organization skeleton)."""
    return f"0192d000-0000-7000-8000-{n:012x}"


# Personas, one per role, shared by every stream. Steps refer to them by key, never by e-mail. Owners add their roles here
# (one line each) in their own step's commit; the role must exist (system role code).
PERSONAS = {  # key: (email, given, family, role code, scope unit or None for the organization)
    "admin": ("demo.admin@meridian.test", "Devika", "Menon", "ORG_ADMIN", None),
    "principal": ("demo.principal@meridian.test", "Prakash", "Nair", "INSTITUTION_ADMIN", U(0xd101)),
    "hod": ("demo.hod@meridian.test", "Harini", "Subramanian", "DEPARTMENT_HEAD", U(0xd121)),
    "faculty": ("demo.faculty@meridian.test", "Farhan", "Qureshi", "FACULTY", U(0xd121)),
    # examination (step 11): the first controller is the organization's bootstrap holder; the others are invited
    "controller": ("demo.controller@meridian.test", "Chitra", "Raman", "EXAM_CONTROLLER", U(0xd101)),
    "controller2": ("demo.controller2@meridian.test", "Imran", "Sheikh", "EXAM_CONTROLLER", U(0xd101)),
    "designer": ("demo.designer@meridian.test", "Deepa", "Krishnan", "ASSESSMENT_DESIGNER", U(0xd101)),
    "proctor": ("demo.invigilator@meridian.test", "Paul", "D'Souza", "EXAM_PROCTOR", U(0xd101)),
}


def _env():
    path = os.path.join(ROOT, "deploy", "compose", ".env")
    if not os.path.exists(path):
        return {}
    return dict(line.strip().split("=", 1) for line in open(path) if "=" in line and not line.startswith("#"))


ENV = _env()


def persona_password(key):
    """Locally the shared development password; on the demo host the per-persona password from the credential register."""
    register = os.environ.get("IVOROVI_DEMO_CREDENTIALS")
    if register:
        with open(register) as f:
            creds = json.load(f)
        return creds[PERSONAS[key][0]]
    return ENV["DEV_USER_PASSWORD"]


class StepError(Exception):
    pass


class Skipped(Exception):
    """Raised by a step that cannot run here (for example no AI provider key, no execution host). Reported, never hidden."""


def http(method, url, body=None, token=None, headers=None, form=None):
    """JSON over HTTP. A 429 waits and repeats (the seeder runs far faster than a person); tests never do this."""
    for _ in range(30):
        h = dict(headers or {})
        data = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            h["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if token:
            h["Authorization"] = "Bearer " + token
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=h)) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read()
            if e.code == 429:
                time.sleep(1.5)
                continue
            try:
                return e.code, (json.loads(raw) if raw else None)
            except ValueError:
                return e.code, {"raw": raw[:200].decode(errors="replace")}
    raise StepError("rate limited too long: " + url)


def ok(result, *codes):
    status, body = result
    if status not in codes:
        raise StepError(f"unexpected {status}: {str(body)[:300]}")
    return body


def idem():
    return {"Idempotency-Key": "demo-" + uuid.uuid4().hex}


class Context:
    """What a step gets: T0, persona tokens, API helpers and the lookup store shared across steps of one run."""

    def __init__(self, t0, state_path):
        self.t0 = t0
        self.state_path = state_path
        self.store = {}
        self._tokens = {}
        if os.path.exists(state_path):
            with open(state_path) as f:
                saved = json.load(f)
            self.store = saved.get("store", {})

    # ---------------------------------------------------------------- time, relative to the reset (never wall-clock dates)
    def at(self, **delta):
        """An instant relative to T0, ISO 8601 UTC, e.g. ctx.at(days=-14) or ctx.at(minutes=-10)."""
        return (self.t0 + _dt.timedelta(**delta)).strftime("%Y-%m-%dT%H:%M:%SZ")

    # ---------------------------------------------------------------- personas
    def token(self, key):
        if key not in self._tokens:
            email = PERSONAS[key][0]
            self._tokens[key] = ok(http("POST", f"{KC}/realms/ivorovi/protocol/openid-connect/token", form={
                "grant_type": "password", "client_id": "ivorovi-dev-cli", "scope": "openid", "username": email,
                "password": persona_password(key)}), 200)["access_token"]
        return self._tokens[key]

    def api(self, method, path, body=None, as_="admin", expect=(200, 201), headers=None):
        return ok(http(method, API + path, body, token=self.token(as_), headers=headers), *expect)

    # ---------------------------------------------------------------- cross-step lookups (server-generated ids)
    def put(self, kind, key, value):
        self.store.setdefault(kind, {})[key] = value
        self.save()

    def get(self, kind, key):
        try:
            return self.store[kind][key]
        except KeyError:
            raise StepError(f"no {kind} '{key}' recorded: does a step that provides it run before this one (needs = [...])?")

    def save(self):
        os.makedirs(os.path.dirname(self.state_path), exist_ok=True)
        with open(self.state_path, "w") as f:
            json.dump({"t0": self.t0.isoformat(), "store": self.store}, f, indent=1, sort_keys=True)

    def skip(self, reason):
        raise Skipped(reason)
