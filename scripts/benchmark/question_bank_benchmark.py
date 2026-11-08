#!/usr/bin/env python3
"""
Increment 2.1 question-bank benchmark against the local stack (real Keycloak token, running API, PostgreSQL).

Uses the separate benchmark organization (bench-imports, see import_benchmark.py); never the demonstration or test
organizations. Synthetic content only.

  * question creation through the API (validation, encrypted key, audit): latency of N creations
  * a bank of 20,000 questions (the rest generated in SQL as drafts with realistic text, search vector and duplicate data)
  * search latency: list page, full-text word, rare word, type filter, tag filter, deep page
  * question detail latency
  * import preview of 200 rows against the 20,000-question bank (exact and near-duplicate checks) and commit

Usage: python3 scripts/benchmark/question_bank_benchmark.py [--size 20000] [--creates 300] [--runs 30] [--out file.json]
"""
import argparse
import json
import os
import statistics
import sys
import time
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "demo"))
import demo_data as d  # noqa: E402

TENANT = "0192e000-0000-7000-8000-0000000000be"
ADMIN = "bench.admin@benchmark.test"
WORDS = ["velocity", "acceleration", "integral", "derivative", "matrix", "vector", "photosynthesis", "enzyme", "equilibrium", "oxidation",
         "polynomial", "probability", "momentum", "refraction", "osmosis", "logarithm", "isotope", "friction", "catalyst", "theorem"]


def pct(ms):
    ms = sorted(ms)
    return {"runs": len(ms), "p50_ms": round(statistics.median(ms), 1), "p95_ms": round(ms[min(len(ms) - 1, int(len(ms) * 0.95))], 1), "max_ms": round(ms[-1], 1)}


def timed(fn, runs):
    out = []
    for _ in range(runs):
        t0 = time.perf_counter()
        fn()
        out.append((time.perf_counter() - t0) * 1000)
    return pct(out)


def api(method, path, token, body=None):
    return d.http(method, d.API + path, body, token=token, headers={"Idempotency-Key": str(uuid.uuid4())})


def content(text):
    return {"format": "ivorovi.content.v1", "blocks": [{"type": "paragraph", "runs": [{"text": text}]}]}


def text(i):
    return f"Bench question {i}: explain the {WORDS[i % 20]} of the {WORDS[(i * 7) % 20]} when x equals {i % 97} and y equals {i % 89}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=20000)
    ap.add_argument("--creates", type=int, default=300)
    ap.add_argument("--runs", type=int, default=30)
    ap.add_argument("--out")
    a = ap.parse_args()
    d.guard()
    if d.psql(f"SELECT count(*) FROM tenant WHERE id = '{TENANT}'") == "0":
        sys.exit("Run scripts/benchmark/import_benchmark.py once first (it creates the benchmark organization).")
    token = d.user_token(ADMIN)
    me = d.psql(f"SELECT id FROM user_account WHERE tenant_id = '{TENANT}' AND email = '{ADMIN}'")
    run = uuid.uuid4().hex[:6]
    bank = d.ok(api("POST", "/api/v1/question-banks", token, {"code": f"QB{run}", "name": f"Benchmark bank {run}", "intendedUses": ["ACADEMIC"],
                                                            "policy": {"approvalPolicy": "STANDARD", "requiredReviews": 1, "approverDistinct": False}}), 201)["id"]
    result = {"bank": bank, "size": a.size}

    # 1. creation through the API
    ms = []
    for i in range(a.creates):
        body = {"type": "SINGLE_CHOICE", "content": content(text(i)), "tags": [WORDS[i % 20]],
                "body": {"options": [{"id": k, "content": content(f"Option {k} for {i}")} for k in "ABCD"]}, "answerKey": {"correct": "B"}}
        t0 = time.perf_counter()
        d.ok(api("POST", f"/api/v1/question-banks/{bank}/questions", token, body), 201)
        ms.append((time.perf_counter() - t0) * 1000)
    result["create_via_api"] = pct(ms)
    result["create_throughput_per_s_single_client"] = round(a.creates / (sum(ms) / 1000), 1)

    # 2. fill the bank to the target size with SQL-generated drafts (same columns the service writes)
    rest = a.size - a.creates
    t0 = time.perf_counter()
    d.psql(f"""
      INSERT INTO question (id, tenant_id, bank_id, status, created_by)
      SELECT ('{run}' || lpad(to_hex(g), 26, '0'))::uuid, '{TENANT}', '{bank}', 'ACTIVE', '{me}' FROM generate_series(1, {rest}) g;
      INSERT INTO question_version (id, tenant_id, question_id, version_no, state, type_code, content, body, marks, tags, plain_text, normalized_text, dup_hash, review_hash, author_id)
      SELECT ('{run}' || lpad(to_hex(g + 900000000), 26, '0'))::uuid, '{TENANT}', ('{run}' || lpad(to_hex(g), 26, '0'))::uuid, 1, 'DRAFT', 'TRUE_FALSE',
             jsonb_build_object('format', 'ivorovi.content.v1', 'blocks', jsonb_build_array(jsonb_build_object('type', 'paragraph', 'runs',
               jsonb_build_array(jsonb_build_object('text', t))))), '{{}}'::jsonb, 1, ARRAY[w1],
             t, n, encode(sha256(convert_to('TRUE_FALSE|' || n || '|', 'UTF8')), 'hex'), md5('r' || t) || md5('s' || t), '{me}'
      FROM (SELECT g, t, trim(regexp_replace(regexp_replace(lower(t), '[.,;:!?]', ' ', 'g'), '[[:space:]]+', ' ', 'g')) AS n, w1 FROM (SELECT g, 'Bench question ' || (g + {a.creates}) || ': explain the ' || (ARRAY{WORDS})[1 + (g + {a.creates}) % 20] || ' of the '
                 || (ARRAY{WORDS})[1 + ((g + {a.creates}) * 7) % 20] || ' when x equals ' || (g + {a.creates}) % 97 || ' and y equals ' || (g + {a.creates}) % 89 AS t,
                 (ARRAY{WORDS})[1 + (g + {a.creates}) % 20] AS w1 FROM generate_series(1, {rest}) g) s0) s;
      UPDATE question q SET current_version_id = v.id FROM question_version v WHERE v.question_id = q.id AND q.bank_id = '{bank}' AND q.current_version_id IS NULL;
      ANALYZE question; ANALYZE question_version;""")
    result["bulk_fill_seconds"] = round(time.perf_counter() - t0, 1)
    result["questions_in_bank"] = int(d.psql(f"SELECT count(*) FROM question WHERE bank_id = '{bank}'"))

    # 3. search
    def search(q):
        return lambda: d.ok(api("GET", f"/api/v1/question-banks/{bank}/questions?{q}", token), 200)
    result["search"] = {
        "first_page": timed(search("size=25"), a.runs),
        "word_common": timed(search("q=velocity&size=25"), a.runs),
        "word_and_number": timed(search("q=momentum%20equals%2042&size=25"), a.runs),
        "phrase_rare": timed(search(f"q=Bench%20question%20{a.size - 7}&size=25"), a.runs),
        "type_filter": timed(search("type=SINGLE_CHOICE&size=25"), a.runs),
        "tag_filter": timed(search("tag=enzyme&size=25"), a.runs),
        "deep_page": timed(search("page=700&size=25"), a.runs),
    }
    some = d.ok(api("GET", f"/api/v1/question-banks/{bank}/questions?size=1", token), 200)["items"][0]["id"]
    result["detail"] = timed(lambda: d.ok(api("GET", f"/api/v1/questions/{some}", token), 200), a.runs)

    # 4. import preview: 200 rows (50 exact duplicates of bank questions, 50 near duplicates, 100 new) against the full bank
    rows = ["type,statement,correct"]
    for i in range(200):
        if i < 50:
            s = text(i + 1000)                                    # SQL-generated statement → duplicate in bank (true/false, same text)
        elif i < 100:
            s = text(i + 2000) + " please"                        # near duplicate
        else:
            s = f"Fresh imported statement number {i} about {WORDS[i % 20]} {run}"
        rows.append(f"TRUE_FALSE,\"{s}\",TRUE")
    csv = ("\n".join(rows) + "\n").encode()
    t0 = time.perf_counter()
    imp = d.ok(upload(token, bank, csv), 201)
    result["import_preview_200_rows_seconds"] = round(time.perf_counter() - t0, 2)
    result["import_outcomes"] = {k: imp[k] for k in ("totalRows", "validRows", "invalidRows", "duplicateRows", "nearDuplicateRows")}
    t0 = time.perf_counter()
    done = d.ok(api("POST", f"/api/v1/question-imports/{imp['id']}/commit", token), 200)
    result["import_commit_seconds"] = round(time.perf_counter() - t0, 2)
    result["import_created"] = done["createdRows"]
    print(json.dumps(result, indent=2))
    if a.out:
        with open(a.out, "w") as f:
            json.dump(result, f, indent=2)


def upload(token, bank, content_bytes):
    import urllib.request
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"bench.csv\"\r\nContent-Type: text/csv\r\n\r\n").encode() \
        + content_bytes + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{d.API}/api/v1/question-banks/{bank}/imports", data=body, method="POST",
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return (r.status, json.loads(r.read()))


if __name__ == "__main__":
    main()
