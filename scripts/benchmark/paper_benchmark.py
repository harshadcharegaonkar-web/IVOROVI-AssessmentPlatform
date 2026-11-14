#!/usr/bin/env python3
"""
QA (D-7) benchmark: assessment publication and paper generation for large assessments, against the local stack (real
Keycloak tokens, running API, PostgreSQL). Synthetic content only, in a run-specific bank and assessment of the seeded
development organization (four-eyes publication needs several real people: author, reviewer, publisher).

  * N approved single-choice questions created through the API (author → review → approval)
  * assessment A: all N questions as fixed items; assessment B: a random rule drawing --draw of the N (shuffled options)
  * per assessment: draft save, validation, submit, review and publication latency
  * paper generation: --papers preview papers per assessment (secure seed, candidate projection), sequential and with
    --threads concurrent clients; p50/p95/max per paper; every paper has the expected number of items and no answer data
  * reproduction of a sample of papers from their stored seed (must reproduce exactly)

The API's per-user write limit (default 60/min) throttles one user generating many papers; run the API with
IVOROVI_RATE_LIMIT_USER_WRITE / _USER_READ / _TENANT raised for the benchmark and say so with the results.

Usage: python3 scripts/benchmark/paper_benchmark.py [--questions 300] [--fixed 200] [--draw 200] [--papers 1000] [--threads 8]
                                                     [--out docs/performance/paper-benchmark.json]
"""
import argparse
import concurrent.futures
import json
import os
import statistics
import sys
import time
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "demo"))
import demo_data as d  # noqa: E402

AUTHOR = "alice.admin@aurora.test"           # assessment.author, question-bank management
FACULTY = "arun.faculty@aurora.test"         # authors questions
REVIEWER = "ines.instadmin@aurora.test"      # reviews questions and assessments
PUBLISHER = "eshan.controller@aurora.test"   # approves questions, publishes assessments
COLLEGE = "0192a000-0000-7000-8000-00000000a101"
SECRET = "Benchmark explanation never shown to candidates"


class Tokens:
    """Staff tokens per role, refreshed before they expire (the realm issues 5-minute access tokens)."""

    def __init__(self, users):
        self.users, self.cache = users, {}

    def __getitem__(self, role):
        tok, at = self.cache.get(role, (None, 0))
        if time.time() - at > 200:
            tok, at = d.user_token(self.users[role]), time.time()
            self.cache[role] = (tok, at)
        return tok


def pct(ms):
    ms = sorted(ms)
    return {"runs": len(ms), "p50_ms": round(statistics.median(ms), 1), "p95_ms": round(ms[min(len(ms) - 1, int(len(ms) * 0.95))], 1),
            "max_ms": round(ms[-1], 1), "mean_ms": round(statistics.fmean(ms), 1)}


def api(method, path, token, body=None, expect=(200, 201)):
    r = d.http(method, d.API + path, body, token=token, headers={"Idempotency-Key": str(uuid.uuid4())})
    return d.ok(r, *expect)


def timed(fn):
    t0 = time.perf_counter()
    out = fn()
    return out, (time.perf_counter() - t0) * 1000


def content(text):
    return {"format": "ivorovi.content.v1", "blocks": [{"type": "paragraph", "runs": [{"text": text}]}]}


def questions(tokens, run, n):
    bank = api("POST", "/api/v1/question-banks", tokens["author"], {
        "orgUnitId": COLLEGE, "code": f"PB{run}"[:40], "name": f"Paper benchmark bank {run}", "intendedUses": ["ACADEMIC"],
        "policy": {"approvalPolicy": "STANDARD", "requiredReviews": 1, "approverDistinct": False}})["id"]
    ids = []
    t0 = time.perf_counter()
    for i in range(n):
        q = api("POST", f"/api/v1/question-banks/{bank}/questions", tokens["faculty"], {
            "type": "SINGLE_CHOICE", "content": content(f"Paper benchmark {run} question {i}: which statement about item {i} holds?"),
            "marks": 2, "negativeMarks": 0.5, "explanation": SECRET, "answerKey": {"correct": "B"},
            "body": {"options": [{"id": k, "content": content(f"Option {k} of question {i}")} for k in "ABCD"]}})["id"]
        api("POST", f"/api/v1/questions/{q}/submit", tokens["faculty"], {})
        api("POST", f"/api/v1/questions/{q}/reviews", tokens["reviewer"], {"decision": "REVIEWED", "checklist": {"accurate": True}})
        api("POST", f"/api/v1/questions/{q}/approve", tokens["publisher"], {"reason": "benchmark"})
        detail = api("GET", f"/api/v1/questions/{q}", tokens["author"])
        ids.append((detail.get("approved") or detail["current"])["id"])
        if (i + 1) % 50 == 0:
            print(f"  {i + 1}/{n} questions approved ({time.perf_counter() - t0:.0f} s)", flush=True)
    return bank, ids


def publish(tokens, run, label, draft):
    steps = {}
    a = api("POST", "/api/v1/assessments", tokens["author"], {"orgUnitId": COLLEGE, "code": f"PB-{label}-{run}"[:40], "purpose": "INTERNAL",
                                                             "title": f"Paper benchmark {label} {run}", "durationMinutes": 180})["id"]
    lock = api("GET", f"/api/v1/assessments/{a}", tokens["author"])["current"]["version"]
    _, steps["draft_save_ms"] = timed(lambda: api("PUT", f"/api/v1/assessments/{a}/draft", tokens["author"],
                                                  {"title": f"Paper benchmark {label} {run}", "durationMinutes": 180, "version": lock, **draft}))
    _, steps["validation_ms"] = timed(lambda: api("GET", f"/api/v1/assessments/{a}/validation", tokens["author"]))
    _, steps["submit_ms"] = timed(lambda: api("POST", f"/api/v1/assessments/{a}/submit", tokens["author"], {}))
    _, steps["review_ms"] = timed(lambda: api("POST", f"/api/v1/assessments/{a}/reviews", tokens["reviewer"], {"decision": "REVIEWED"}))
    _, steps["publish_ms"] = timed(lambda: api("POST", f"/api/v1/assessments/{a}/publish", tokens["publisher"], {}))
    return a, {k: round(v, 1) for k, v in steps.items()}


def papers(tokens, assessment, count, threads, expected_items):
    def one(_):
        paper, ms = timed(lambda: api("POST", f"/api/v1/assessments/{assessment}/preview-papers", tokens["publisher"]))
        text = json.dumps(paper)
        items = len(paper.get("items", []))
        clean = SECRET not in text and "answerKey" not in text and '"correct"' not in text
        return paper["id"], ms, items, clean

    seq_n = min(count, max(1, count // 4))
    sequential = [one(i) for i in range(seq_n)]
    t0 = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=threads) as pool:
        concurrent_ = list(pool.map(one, range(count - seq_n)))
    wall = time.perf_counter() - t0
    every = sequential + concurrent_
    assert all(p[2] == expected_items for p in every), f"papers with a wrong number of items: {[p[2] for p in every if p[2] != expected_items][:5]}"
    assert all(p[3] for p in every), "answer information in a paper"
    sample = [p[0] for p in every[:: max(1, len(every) // 20)]][:20]
    repro = []
    for pid in sample:
        r, ms = timed(lambda: api("POST", f"/api/v1/assessment-papers/{pid}/reproduce", tokens["publisher"]))
        repro.append((ms, bool(r["identical"])))
    return {"papers": len(every), "items_per_paper": expected_items, "sequential": pct([p[1] for p in sequential]),
            f"concurrent_{threads}_clients": pct([p[1] for p in concurrent_]), "concurrent_throughput_papers_per_s": round(len(concurrent_) / wall, 1),
            "reproduction": {"sampled": len(repro), "all_identical": all(x[1] for x in repro), **pct([x[0] for x in repro])},
            "answer_information_in_any_paper": False}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--questions", type=int, default=300)
    ap.add_argument("--fixed", type=int, default=200)
    ap.add_argument("--draw", type=int, default=200)
    ap.add_argument("--papers", type=int, default=1000)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--out")
    a = ap.parse_args()
    d.guard()
    run = uuid.uuid4().hex[:8]
    tokens = Tokens({"author": AUTHOR, "faculty": FACULTY, "reviewer": REVIEWER, "publisher": PUBLISHER})
    started = time.perf_counter()
    print(f"run {run}: creating {a.questions} approved questions", flush=True)
    bank, ids = questions(tokens, run, a.questions)
    result = {"run": run, "bank": bank, "questions": a.questions, "api": d.API, "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}

    print(f"assessment A: {a.fixed} fixed items", flush=True)
    fixed, steps = publish(tokens, run, "F", {"items": [{"questionVersionId": q} for q in ids[: a.fixed]]})
    result["fixed"] = {"assessment": fixed, "items": a.fixed, "publication": steps}
    print(f"  publication {steps}; generating {a.papers} papers", flush=True)
    result["fixed"]["papers"] = papers(tokens, fixed, a.papers, a.threads, a.fixed)

    print(f"assessment B: random rule drawing {a.draw} of {a.questions}", flush=True)
    drawn, steps = publish(tokens, run, "R", {"shuffleOptions": True, "rules": [{"bankId": bank, "count": a.draw, "marks": 2}]})
    result["random"] = {"assessment": drawn, "pool": a.questions, "draw": a.draw, "publication": steps}
    print(f"  publication {steps}; generating {a.papers} papers", flush=True)
    result["random"]["papers"] = papers(tokens, drawn, a.papers, a.threads, a.draw)
    result["total_s"] = round(time.perf_counter() - started, 1)
    text = json.dumps(result, indent=2)
    print(text)
    if a.out:
        with open(a.out, "w") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
