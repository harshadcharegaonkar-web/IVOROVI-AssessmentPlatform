#!/usr/bin/env python3
"""
KI-75 independent rerun (QA, decision D-12): measures the bank question list/search API with its own timing and
percentiles (p50/p95/p99), independent of scripts/benchmark/question_bank_benchmark.py's measurement. Uses only the
token helper of scripts/demo/demo_data.py. Local stack only.

  per query type: 20 warm-up requests, then N sequential requests (p50/p95/p99/max), then N requests from C concurrent
  clients (p50/p95/p99, throughput). Every response must be 200 with a page of items.

Usage: python3 docs/performance/ki75-rerun-measure.py --bank <id> [--n 300] [--clients 8] [--rare-index 19993] --out file.json
"""
import argparse
import concurrent.futures
import json
import os
import statistics
import sys
import time
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "demo"))
import demo_data as d  # noqa: E402

ADMIN = "bench.admin@benchmark.test"


def pct(ms):
    s = sorted(ms)
    at = lambda p: s[min(len(s) - 1, max(0, int(round(p * len(s))) - 1))]  # noqa: E731  nearest-rank
    return {"n": len(s), "p50_ms": round(statistics.median(s), 1), "p95_ms": round(at(0.95), 1), "p99_ms": round(at(0.99), 1), "max_ms": round(s[-1], 1)}


class Token:
    def __init__(self):
        self.tok, self.at = None, 0

    def get(self):
        if time.time() - self.at > 200:
            self.tok, self.at = d.user_token(ADMIN), time.time()
        return self.tok


def get(url, token):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        body = r.read()
        status = r.status
    ms = (time.perf_counter() - t0) * 1000
    page = json.loads(body)
    assert status == 200 and isinstance(page.get("items"), list), f"{url}: {status}"
    return ms, page.get("total")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bank", required=True)
    ap.add_argument("--n", type=int, default=300)
    ap.add_argument("--clients", type=int, default=8)
    ap.add_argument("--rare-index", type=int, default=19993)
    ap.add_argument("--out")
    a = ap.parse_args()
    d.guard()
    tok = Token()
    base = f"{d.API}/api/v1/question-banks/{a.bank}/questions?"
    queries = {
        "first_page": "size=25",
        "word_common": "q=velocity&size=25",
        "word_and_number": "q=momentum%20equals%2042&size=25",
        "phrase_rare": f"q=Bench%20question%20{a.rare_index}&size=25",
        "type_filter": "type=SINGLE_CHOICE&size=25",
        "tag_filter": "tag=enzyme&size=25",
        "deep_page": "page=700&size=25",
        "approved_only": "approvedOnly=true&size=25",
    }
    out = {"bank": a.bank, "n": a.n, "clients": a.clients, "api": d.API, "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "queries": {}}
    for name, q in queries.items():
        for _ in range(20):
            get(base + q, tok.get())
        seq, total = [], None
        for _ in range(a.n):
            ms, total = get(base + q, tok.get())
            seq.append(ms)
        t0 = time.perf_counter()
        token = tok.get()
        with concurrent.futures.ThreadPoolExecutor(max_workers=a.clients) as pool:
            conc = [r[0] for r in pool.map(lambda _: get(base + q, token), range(a.n))]
        wall = time.perf_counter() - t0
        out["queries"][name] = {"query": q, "total_matches": total, "sequential": pct(seq), f"concurrent_{a.clients}": pct(conc),
                                "concurrent_requests_per_s": round(a.n / wall, 1)}
        print(name, json.dumps(out["queries"][name]), flush=True)
    if a.out:
        with open(a.out, "w") as f:
            f.write(json.dumps(out, indent=2) + "\n")


if __name__ == "__main__":
    main()
