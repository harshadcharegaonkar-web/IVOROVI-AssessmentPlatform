#!/usr/bin/env python3
"""Outbox publishing under bulk load (Increment 2.0-E, KI-64), against the running stack.

Inserts N events for one synthetic organization in a single statement (as a bulk import would), then measures how
fast the running administrative API's publisher (4 workers, 500-event batches, publisher confirms) marks them
published, and checks RabbitMQ received each of them on a temporary queue bound to the benchmark routing key.

    python3 scripts/benchmark/outbox_benchmark.py [events=50000] [--out docs/performance/outbox-throughput.json]

Requires: compose stack (postgres, rabbitmq with management API), the API running with the publisher enabled,
deploy/compose/.env. Standard library only.
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def env():
    out = {}
    for line in open(os.path.join(ROOT, "deploy/compose/.env")):
        if "=" in line and not line.startswith("#"):
            k, v = line.strip().split("=", 1)
            out[k] = v
    return out


E = env()


def psql(sql):
    r = subprocess.run(["docker", "exec", "-e", f"PGPASSWORD={E['IVOROVI_OWNER_PASSWORD']}", "ivorovi-postgres-1", "psql", "-h", "127.0.0.1",
                        "-U", "ivorovi_owner", "-d", "ivorovi", "-tAc", sql], capture_output=True, text=True, check=True)
    return r.stdout.strip()


def rabbit(method, path, body=None):
    req = urllib.request.Request(f"http://localhost:15672/api{path}", method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Basic " + base64.b64encode(f"ivorovi:{E['RABBITMQ_PASSWORD']}".encode()).decode()})
    with urllib.request.urlopen(req) as r:
        data = r.read()
        return json.loads(data) if data else None


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 50000
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(ROOT, "docs/performance/outbox-throughput.json")
    tenant = str(uuid.uuid4())
    queue = f"bench.outbox.{tenant[:8]}"
    rabbit("PUT", f"/queues/%2F/{queue}", {"durable": True, "auto_delete": False})
    rabbit("POST", f"/bindings/%2F/e/ivorovi.events/q/{queue}", {"routing_key": "bench.outbox.#"})
    psql(f"INSERT INTO tenant (id, code, name, status) VALUES ('{tenant}', 'bench-{tenant[:8]}', 'Outbox benchmark (synthetic)', 'ACTIVE')")
    backlog = int(psql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL"))
    t0 = time.time()
    psql(f"""INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload)
             SELECT gen_random_uuid(), '{tenant}', 'bench', g::text, 'bench.outbox.created', jsonb_build_object('n', g) FROM generate_series(1, {n}) g""")
    inserted = time.time()
    first = None
    samples = []
    while True:
        pending = int(psql(f"SELECT count(*) FROM outbox_event WHERE tenant_id = '{tenant}' AND published_at IS NULL"))
        now = time.time()
        samples.append({"t": round(now - inserted, 1), "pending": pending})
        if first is None and pending < n:
            first = now
        if pending == 0 or now - inserted > 600:
            break
        time.sleep(0.5)
    done = time.time()
    delivered = 0
    for _ in range(40):
        delivered = rabbit("GET", f"/queues/%2F/{queue}")["messages"]
        if delivered >= n:
            break
        time.sleep(0.5)
    rabbit("DELETE", f"/queues/%2F/{queue}")
    psql(f"DELETE FROM outbox_event WHERE tenant_id = '{tenant}'")
    psql(f"DELETE FROM tenant WHERE id = '{tenant}'")
    drain = done - (first or inserted)
    result = {
        "date": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "events": n, "otherBacklogAtStart": backlog,
        "insertSeconds": round(inserted - t0, 2), "pickupLatencySeconds": round((first or done) - inserted, 2),
        "drainSeconds": round(drain, 2), "eventsPerSecond": round(n / drain) if drain > 0 else None,
        "deliveredToQueue": delivered, "lostOrDuplicated": delivered - n, "samples": samples[:: max(1, len(samples) // 20)],
    }
    json.dump(result, open(out, "w"), indent=2)
    print(json.dumps({k: v for k, v in result.items() if k != "samples"}, indent=2))


if __name__ == "__main__":
    main()
