#!/usr/bin/env python3
"""
Student import benchmark (increment 1.6) against the local stack, with real Keycloak tokens.

Creates (once) a separate benchmark organization "Import Benchmark Org" (code bench-imports) so neither the Meridian
demonstration data nor the test organizations are touched, then for each size:
  generate a synthetic CSV → upload → map (suggested mapping) → background validation → confirm (valid rows only)
  → background processing, and records wall-clock times, API process memory (peak RSS and heap after GC sampled
  with jcmd), and database activity (pg_stat_database deltas).
Optionally simulates a crashed API during processing (--recovery N): the API process is killed, restarted, and the
job must finish with every row imported exactly once.

Usage: python3 scripts/benchmark/import_benchmark.py [--sizes 100,1000,5000,10000] [--recovery 2000] [--out file.json]
Development only. Synthetic data only.
"""
import argparse
import csv
import io
import json
import os
import random
import subprocess
import sys
import threading
import time
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "demo"))
import demo_data as d  # noqa: E402  (reuses the local-stack helpers: psql, http, Keycloak provisioning)

TENANT = "0192e000-0000-7000-8000-0000000000be"
B = lambda n: f"0192e000-0000-7000-8000-{n:012x}"  # noqa: E731
UNIV, COLLEGE, DEPT = B(0xb001), B(0xb002), B(0xb003)
ADMIN = "bench.admin@benchmark.test"
FIRST = ["Aarav", "Diya", "Ishaan", "Kavya", "Rohan", "Saanvi", "Vihaan", "Ananya", "Arjun", "Meera", "Kabir", "Nisha", "Dev", "Riya", "Aditya", "Tara"]
LAST = ["Sharma", "Iyer", "Nair", "Reddy", "Menon", "Gupta", "Rao", "Patel", "Das", "Khan", "Joshi", "Pillai", "Bose", "Shah", "Kulkarni", "Verma"]


def letters(n):
    s = ""
    while True:
        s += "abcdefghijklmnopqrstuvwxyz"[n % 26]
        n //= 26
        if n == 0:
            return s


def skeleton():
    if d.psql(f"SELECT count(*) FROM tenant WHERE id = '{TENANT}'") != "0":
        return
    d.TENANT = TENANT  # keycloak_user binds the sign-in to this organization
    rows = [f"INSERT INTO tenant (id, code, name, status) VALUES ('{TENANT}', 'bench-imports', 'Import Benchmark Org', 'ACTIVE')"]
    paths = {}
    for uid, typ, parent, code in [(UNIV, "UNIVERSITY", None, "BU"), (COLLEGE, "COLLEGE", UNIV, "BCOL"), (DEPT, "DEPARTMENT", COLLEGE, "BDEP")]:
        paths[uid] = (paths[parent] + "." if parent else "") + uid.replace("-", "")
        rows.append(f"INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES ('{uid}', '{TENANT}', "
                    f"{'NULL' if parent is None else repr(parent)}, '{typ}', '{code}', 'Benchmark {code}', '{paths[uid]}')")
    subject = d.keycloak_user(d.provisioner_token(), ADMIN, "Bench", "Admin")
    rows.append(f"INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name, family_name, status) VALUES "
                f"('{B(0xb101)}', '{TENANT}', '{subject}', '{ADMIN}', 'Bench Admin', 'Bench', 'Admin', 'ACTIVE')")
    rows.append(f"INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES ('{B(0xb201)}', '{TENANT}', '{B(0xb101)}', "
                f"'{d.ROLE['ORG_ADMIN']}', 'TENANT', '{TENANT}')")
    d.psql("; ".join(rows))


def api(method, path, token, body=None, headers=None):
    return d.http(method, d.API + path, body, token=token, headers=headers)


def structure(token):
    """Program, course, year and two batches (created once; looked up afterwards)."""
    found = d.ok(api("GET", "/api/v1/academics/batches?size=100", token), 200)["items"]
    codes = {b["code"] for b in found}
    if {"BB-A", "BB-B"} <= codes:
        return
    h = lambda: {"Idempotency-Key": f"bench-{uuid.uuid4()}"}  # noqa: E731
    p = d.ok(api("POST", "/api/v1/academics/programs", token, {"code": "BBTECH", "name": "Benchmark B.Tech", "qualificationLevel": "UNDERGRADUATE",
                                                              "ownerOrgUnitId": COLLEGE, "termSystem": "SEMESTER", "durationYears": 4}, h()), 201)
    c = d.ok(api("POST", "/api/v1/academics/courses", token, {"code": "BCSE", "name": "Benchmark CSE", "programId": p["id"], "departmentId": DEPT}, h()), 201)
    yr = time.gmtime().tm_year
    y = d.ok(api("POST", "/api/v1/academics/academic-years", token, {"code": f"BY{yr}", "name": f"Benchmark {yr}", "ownerOrgUnitId": COLLEGE,
                                                                    "startDate": f"{yr}-07-01", "endDate": f"{yr + 1}-06-30"}, h()), 201)
    for code in ("BB-A", "BB-B"):
        d.ok(api("POST", "/api/v1/academics/batches", token, {"code": code, "name": f"Benchmark {code}", "courseId": c["id"], "academicYearId": y["year"]["id"],
                                                             "admissionYear": yr}, h()), 201)


def dataset(n, run):
    """Realistic synthetic rows: personal details, identifiers, admission, enrolment and one guardian."""
    out = io.StringIO()
    w = csv.writer(out)
    w.writerow(["Student ID", "Registration number", "First name", "Last name", "Date of birth", "Gender", "Personal email", "Mobile",
                "Admission number", "Admission date", "Batch", "Semester", "Student status", "Guardian 1 name", "Guardian 1 relationship", "Guardian 1 mobile"])
    rnd = random.Random(n)
    tag = "".join("abcdefghijklmnop"[int(ch, 16)] for ch in run.lower())   # letters only: names may not contain digits
    adm = time.strftime("%Y-%m-%d", time.gmtime(time.time() - 40 * 86400))
    for i in range(n):
        first, last = rnd.choice(FIRST), rnd.choice(LAST) + letters(i) + tag
        w.writerow([f"B{run}-{i:05d}", f"R{run}-{i:05d}", first, last, f"{2004 + i % 3}-{1 + i % 12:02d}-{1 + i % 28:02d}", "Female" if i % 2 else "Male",
                    f"{first.lower()}.{last.lower()}.{run.lower()}@example.test", f"+91 9{rnd.randint(100000000, 999999999)}", f"A{run}-{i:05d}", adm,
                    "BB-A" if i % 2 else "BB-B", "1", "Active" if i % 4 else "", f"Parent {last}", "Mother" if i % 2 else "Father",
                    f"+91 8{rnd.randint(100000000, 999999999)}"])
    return out.getvalue().encode("utf-8")


def upload(token, name, content):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: text/csv\r\n\r\n").encode() + content + \
           f"\r\n--{boundary}--\r\n".encode()
    import urllib.request
    req = urllib.request.Request(f"{d.API}/api/v1/student-imports?institutionId={COLLEGE}&importKey=bench-{uuid.uuid4().hex}", data=body, method="POST",
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def api_pid():
    out = subprocess.run(["pgrep", "-f", "^java -jar.*ivorovi-api"], capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else None


class Sampler(threading.Thread):
    """Samples the API process: peak resident memory and heap in use (jcmd GC.heap_info)."""

    def __init__(self):
        super().__init__(daemon=True)
        self.stop, self.rss, self.heap = False, 0, 0

    def run(self):
        while not self.stop:
            pid = api_pid()
            if pid:
                try:
                    self.rss = max(self.rss, int(open(f"/proc/{pid}/status").read().split("VmRSS:")[1].split()[0]) // 1024)
                    info = subprocess.run(["jcmd", str(pid), "GC.heap_info"], capture_output=True, text=True, timeout=10).stdout
                    for line in info.splitlines():
                        if "used" in line and ("garbage-first heap" in line or "total" in line):
                            used = line.split("used")[1].split("K")[0].strip()
                            self.heap = max(self.heap, int(used) // 1024)
                            break
                except Exception:  # noqa: BLE001 - best-effort sampling
                    pass
            time.sleep(1)


def db_stats():
    raw = d.psql("SELECT xact_commit, tup_inserted, tup_updated, tup_deleted, blks_read, blks_hit FROM pg_stat_database WHERE datname = 'ivorovi'")
    return [int(x) for x in raw.split("|")]


def wait(token, job_id, until, timeout=3600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            j = d.ok(api("GET", f"/api/v1/student-imports/{job_id}", d.user_token(ADMIN)), 200)   # fresh token: imports outlive access tokens
        except Exception:  # noqa: BLE001 - API restarting during the recovery test
            time.sleep(2)
            continue
        if j["status"] in until:
            return j
        time.sleep(1)
    raise SystemExit(f"timeout waiting for {until}")


def run_one(n, recovery=False):
    token = d.user_token(ADMIN)
    run = uuid.uuid4().hex[:6].upper()
    content = dataset(n, run)
    s0 = db_stats()
    sampler = Sampler()
    sampler.start()
    t0 = time.time()
    job = upload(token, f"bench-{n}.csv", content)
    t_upload = time.time() - t0
    cols = [{"column": m["column"], "field": m["field"]} for m in job["mapping"] if m.get("field")]
    d.ok(api("PUT", f"/api/v1/student-imports/{job['id']}/mapping", token, {"columns": cols, "version": job["version"]}), 200)
    t1 = time.time()
    job = wait(token, job["id"], {"READY", "FAILED"})
    t_validate = time.time() - t1
    c = job["counts"]
    expected = c["toCreate"] + c["toUpdate"]
    d.ok(api("POST", f"/api/v1/student-imports/{job['id']}/confirm", token, {"mode": "VALID_ROWS_ONLY", "version": job["version"], "expectedRows": expected,
                                                                            "acknowledged": True}), 200)
    t2 = time.time()
    crash = None
    if recovery:
        while d.ok(api("GET", f"/api/v1/student-imports/{job['id']}", d.user_token(ADMIN)), 200)["progress"]["processed"] < n // 3:
            time.sleep(1)
        pid = api_pid()
        crash = {"killedAtProcessed": d.ok(api("GET", f"/api/v1/student-imports/{job['id']}", d.user_token(ADMIN)), 200)["progress"]["processed"]}
        os.kill(pid, 9)
        subprocess.Popen(["bash", "-c", f"cd {d.ROOT} && . scripts/api-env.sh && exec java -jar backend/api/build/libs/ivorovi-api.jar --spring.profiles.active=dev"],
                         stdout=open("/tmp/claude-0/api.log", "a"), stderr=subprocess.STDOUT, start_new_session=True)
        token = None
        while token is None:
            try:
                if d.http("GET", d.API + "/actuator/health")[0] == 200:
                    token = d.user_token(ADMIN)
            except Exception:  # noqa: BLE001
                time.sleep(2)
        crash["apiBackAfterSeconds"] = round(time.time() - t2, 1)
    job = wait(token or d.user_token(ADMIN), job["id"], {"COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED"})
    t_process = time.time() - t2
    sampler.stop = True
    s1 = db_stats()
    created = int(d.psql(f"SELECT count(*) FROM student WHERE tenant_id = '{TENANT}' AND student_no LIKE 'B{run}-%'"))
    dupes = int(d.psql(f"SELECT count(*) FROM (SELECT lower(registration_no) FROM student WHERE tenant_id = '{TENANT}' AND registration_no LIKE 'R{run}-%' "
                       "GROUP BY 1 HAVING count(*) > 1) x"))
    p = job["progress"]
    res = {"rows": n, "fileKB": round(len(content) / 1024), "status": job["status"], "valid": c["valid"], "imported": p["imported"], "failed": p["failed"],
           "studentsInDb": created, "duplicateStudents": dupes, "uploadSeconds": round(t_upload, 2), "validationSeconds": round(t_validate, 1),
           "processingSeconds": round(t_process, 1), "rowsPerSecond": round(p["imported"] / t_process, 1) if t_process else None,
           "apiPeakRssMB": sampler.rss, "apiPeakHeapUsedMB": sampler.heap, "dbCommits": s1[0] - s0[0], "dbRowsInserted": s1[1] - s0[1],
           "dbRowsUpdated": s1[2] - s0[2], "dbBlocksRead": s1[4] - s0[4], "dbBlocksHit": s1[5] - s0[5], "attempts": job["attempts"]}
    if crash:
        res["recovery"] = crash
    print(json.dumps(res), flush=True)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sizes", default="100,1000,5000,10000")
    ap.add_argument("--recovery", type=int, default=0)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    d.guard()
    skeleton()
    structure(d.user_token(ADMIN))
    results = [run_one(int(n)) for n in a.sizes.split(",") if n]
    if a.recovery:
        results.append(run_one(a.recovery, recovery=True))
    if a.out:
        with open(a.out, "w") as f:
            json.dump(results, f, indent=2)


if __name__ == "__main__":
    main()
