#!/usr/bin/env python3
"""
Increment 1.7 benchmark against the local stack, using the separate benchmark organization of the import benchmark
(36,400 synthetic students; never the Meridian demonstration data or the test organizations).

Measures, with real Keycloak tokens and the running API:
  * dashboard latency (executive, academic, students, staff, imports) and report latency (first page, deep page)
  * full CSV and XLSX export of the students report (all 36,400 rows)
  * API-key authentication overhead compared with a bearer token (same endpoint)
  * webhook delivery throughput: N deliveries queued for one endpoint, delivered by the scheduled dispatcher to a local
    HTTP receiver that verifies every signature

Usage: python3 scripts/benchmark/insights_benchmark.py [--runs 15] [--webhooks 1000] [--out file.json]
Development only. Synthetic data only.
"""
import argparse
import hashlib
import hmac
import http.server
import json
import os
import statistics
import sys
import threading
import time
import urllib.request
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "demo"))
import demo_data as d  # noqa: E402

TENANT = "0192e000-0000-7000-8000-0000000000be"
ADMIN = "bench.admin@benchmark.test"


def timed(url, headers, runs):
    ms = []
    size = 0
    for _ in range(runs):
        req = urllib.request.Request(url, headers=headers)
        t0 = time.perf_counter()
        with urllib.request.urlopen(req, timeout=120) as r:
            body = r.read()
            assert r.status == 200, r.status
        ms.append((time.perf_counter() - t0) * 1000)
        size = len(body)
    ms.sort()
    return {"runs": runs, "p50_ms": round(statistics.median(ms), 1), "p95_ms": round(ms[min(len(ms) - 1, int(len(ms) * 0.95))], 1),
            "max_ms": round(ms[-1], 1), "bytes": size}


def webhook_throughput(token, n):
    secret_box = {}
    received = []
    bad = []
    done = threading.Event()

    class H(http.server.BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            body = self.rfile.read(int(self.headers["Content-Length"]))
            sig = self.headers["Ivorovi-Signature"]
            t, v1 = sig.split(",")
            expect = hmac.new(secret_box["s"].encode(), (t[2:] + "." + body.decode()).encode(), hashlib.sha256).hexdigest()
            (received if hmac.compare_digest(expect, v1[3:]) else bad).append(time.perf_counter())
            self.send_response(204)
            self.end_headers()
            if len(received) + len(bad) >= n + 1:
                done.set()

        def log_message(self, *a):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_address[1]
    ep = d.ok(d.http("POST", f"{d.API}/api/v1/webhooks", {"name": f"Benchmark {uuid.uuid4().hex[:6]}", "url": f"http://127.0.0.1:{port}/hook",
                     "eventTypes": ["student.created"]}, token=token), 201)
    secret_box["s"] = ep["signingSecret"]
    eid = ep["endpoint"]["id"]
    d.ok(d.http("POST", f"{d.API}/api/v1/webhooks/{eid}/test", None, token=token), 200)   # warm-up + signature check
    d.psql(f"""INSERT INTO webhook_delivery (id, tenant_id, endpoint_id, event_id, event_type, payload)
               SELECT gen_random_uuid(), '{TENANT}', '{eid}', gen_random_uuid(), 'student.created',
                      jsonb_build_object('id', gen_random_uuid(), 'type', 'student.created', 'tenantId', '{TENANT}', 'data', jsonb_build_object('resourceType', 'student'))
               FROM generate_series(1, {n})""")
    t0 = time.perf_counter()
    done.wait(timeout=600)
    elapsed = time.perf_counter() - t0
    server.shutdown()
    d.ok(d.http("DELETE", f"{d.API}/api/v1/webhooks/{eid}", None, token=token), 204)
    got = len(received) - 1
    return {"queued": n, "delivered_with_valid_signature": got, "invalid_signatures": len(bad), "seconds_until_last": round(elapsed, 1),
            "deliveries_per_second": round(got / elapsed, 1) if elapsed else None,
            "note": "includes up to one dispatcher poll interval (5 s) of waiting before the first batch"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=15)
    ap.add_argument("--webhooks", type=int, default=1000)
    ap.add_argument("--out")
    a = ap.parse_args()
    students = int(d.psql(f"SELECT count(*) FROM student WHERE tenant_id = '{TENANT}'"))
    if students < 1000:
        sys.exit("Run scripts/benchmark/import_benchmark.py first (it creates the benchmark organization and its students).")
    token = d.user_token(ADMIN)
    bearer = {"Authorization": f"Bearer {token}"}
    res = {"organization_students": students, "dashboards": {}, "reports": {}, "exports": {}}
    for name in ["executive", "academic", "students", "staff", "imports"]:
        res["dashboards"][name] = timed(f"{d.API}/api/v1/insights/{name}", bearer, a.runs)
        print(f"dashboard {name:10} {res['dashboards'][name]}")
    last = students // 25
    for name, q in [("students first page", "students?size=25"), ("students last page", f"students?size=25&page={last}"),
                    ("students filtered (status, sort)", "students?size=25&status=ACTIVE&sort=name"), ("enrolments", "enrolments?size=25"),
                    ("imports", "imports?size=25")]:
        res["reports"][name] = timed(f"{d.API}/api/v1/reports/{q}", bearer, a.runs)
        print(f"report {name:34} {res['reports'][name]}")
    for fmt in ["CSV", "XLSX"]:
        res["exports"][fmt] = timed(f"{d.API}/api/v1/reports/students/export?format={fmt}", bearer, 3)
        print(f"export students {fmt} {res['exports'][fmt]}")
    client = d.ok(d.http("POST", f"{d.API}/api/v1/api-clients", {"name": f"Benchmark {uuid.uuid4().hex[:6]}", "scopes": ["org_unit.read"]}, token=token), 201)
    key = {"X-Api-Key": client["apiKey"]}
    res["api_key_auth"] = {"bearer": timed(f"{d.API}/api/v1/org-units/tree", bearer, 200), "api_key": timed(f"{d.API}/api/v1/org-units/tree", key, 200)}
    print(f"auth {res['api_key_auth']}")
    d.ok(d.http("POST", f"{d.API}/api/v1/api-clients/{client['client']['id']}/revoke", {"reason": "benchmark finished"}, token=token), 200)
    res["webhooks"] = webhook_throughput(token, a.webhooks)
    print(f"webhooks {res['webhooks']}")
    if a.out:
        with open(a.out, "w") as f:
            json.dump(res, f, indent=2)


if __name__ == "__main__":
    d.guard() if hasattr(d, "guard") else None
    main()
