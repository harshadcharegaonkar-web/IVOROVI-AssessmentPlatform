#!/usr/bin/env python3
"""
KI-59 benchmark: authorization overhead of API keys with live permission revalidation, under concurrent load.

Uses the separate benchmark organization (36,400 synthetic students; never Meridian or test data). For 1, 8 and 32
concurrent clients it runs the same read request (GET /api/v1/org-units/tree) for a fixed duration with:
  bearer      an administrator's access token (cached principal; the baseline)
  delegated   a delegated API key (owner's CURRENT grants re-checked on every request)
  service     a service-identity API key (identity's role grants ∩ approved envelope, re-checked on every request)
and records throughput and latency percentiles. A second administrator (created once) approves the identity's envelope.

Usage: python3 scripts/benchmark/api_key_auth_benchmark.py [--seconds 20] [--levels 1,8,32] [--out file.json]
Development only. Synthetic data only.
"""
import argparse
import json
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "demo"))
sys.path.insert(0, os.path.dirname(__file__))
import demo_data as d  # noqa: E402
import import_benchmark as ib  # noqa: E402

APPROVER = "bench.approver@benchmark.test"
APPROVER_ID = ib.B(0xb102)


def ensure_approver():
    if d.psql(f"SELECT count(*) FROM user_account WHERE id = '{APPROVER_ID}'") != "0":
        return
    d.TENANT = ib.TENANT
    subject = d.keycloak_user(d.provisioner_token(), APPROVER, "Bench", "Approver")
    d.psql(f"INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name, family_name, status) VALUES "
           f"('{APPROVER_ID}', '{ib.TENANT}', '{subject}', '{APPROVER}', 'Bench Approver', 'Bench', 'Approver', 'ACTIVE'); "
           f"INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES ('{ib.B(0xb202)}', '{ib.TENANT}', "
           f"'{APPROVER_ID}', '{d.ROLE['ORG_ADMIN']}', 'TENANT', '{ib.TENANT}')")


def run(headers, seconds, threads):
    lat, errors = [], [0]
    stop = time.time() + seconds
    lock = threading.Lock()

    def worker():
        local = []
        while time.time() < stop:
            req = urllib.request.Request(f"{d.API}/api/v1/org-units/tree", headers=headers)
            t0 = time.perf_counter()
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    r.read()
                    ok = r.status == 200
            except urllib.error.HTTPError:
                ok = False
            local.append((time.perf_counter() - t0) * 1000)
            if not ok:
                with lock:
                    errors[0] += 1
        with lock:
            lat.extend(local)

    ts = [threading.Thread(target=worker) for _ in range(threads)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    lat.sort()
    pct = lambda p: round(lat[min(len(lat) - 1, int(len(lat) * p))], 1)  # noqa: E731
    return {"threads": threads, "requests": len(lat), "errors": errors[0], "rps": round(len(lat) / seconds, 1), "p50_ms": round(statistics.median(lat), 1),
            "p95_ms": pct(0.95), "p99_ms": pct(0.99)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=20)
    ap.add_argument("--levels", default="1,8,32")
    ap.add_argument("--out")
    a = ap.parse_args()
    ensure_approver()
    admin = d.user_token(ib.ADMIN)
    approver = d.user_token(APPROVER)
    me = d.ok(d.http("GET", f"{d.API}/api/v1/me", token=admin), 200)["userId"]
    delegated = d.ok(d.http("POST", f"{d.API}/api/v1/api-clients", {"name": f"Bench delegated {uuid.uuid4().hex[:6]}", "scopes": ["org_unit.read"]}, token=admin), 201)
    ident = d.ok(d.http("POST", f"{d.API}/api/v1/service-identities", {"name": f"Bench identity {uuid.uuid4().hex[:6]}", "purpose": "KI-59 benchmark",
                 "businessCustodianId": me, "technicalCustodianId": APPROVER_ID, "envelope": ["org_unit.read"]}, token=admin), 201)
    d.ok(d.http("POST", f"{d.API}/api/v1/service-identities/{ident['id']}/envelope/approve", {}, token=approver), 200)
    role = d.ok(d.http("POST", f"{d.API}/api/v1/roles", {"name": f"Bench unit reader {uuid.uuid4().hex[:6]}", "permissions": ["org_unit.read"]}, token=admin,
                       headers={"Idempotency-Key": uuid.uuid4().hex}), 201)["role"]["id"]
    d.ok(d.http("POST", f"{d.API}/api/v1/service-identities/{ident['id']}/roles", {"roleId": role}, token=admin), 201)
    service = d.ok(d.http("POST", f"{d.API}/api/v1/api-clients", {"name": f"Bench service {uuid.uuid4().hex[:6]}", "scopes": ["org_unit.read"],
                          "serviceIdentityId": ident["id"]}, token=admin), 201)
    creds = {"bearer": {"Authorization": f"Bearer {admin}"}, "delegated": {"X-Api-Key": delegated["apiKey"]}, "service": {"X-Api-Key": service["apiKey"]}}
    for h in creds.values():   # warm-up
        run(h, 2, 2)
    results = []
    for level in [int(x) for x in a.levels.split(",")]:
        for name, h in creds.items():
            if name == "bearer":
                h = {"Authorization": f"Bearer {d.user_token(ib.ADMIN)}"}   # fresh token per level (5-minute lifetime)
            r = run(h, a.seconds, level)
            r["credential"] = name
            results.append(r)
            print(json.dumps(r))
    for c in (delegated, service):
        d.ok(d.http("POST", f"{d.API}/api/v1/api-clients/{c['client']['id']}/revoke", {"reason": "benchmark finished"}, token=admin), 200)
    d.ok(d.http("POST", f"{d.API}/api/v1/service-identities/{ident['id']}/disable", {"reason": "benchmark finished"}, token=admin), 200)
    if a.out:
        with open(a.out, "w") as f:
            json.dump({"seconds": a.seconds, "endpoint": "GET /api/v1/org-units/tree", "results": results}, f, indent=2)


if __name__ == "__main__":
    main()
