#!/usr/bin/env python3
"""Repeatable demonstration dataset (LOCAL DEVELOPMENT ONLY; entirely synthetic, no real personal data).

The demonstration organization "Meridian Education Trust" is separate from the Aurora/Borealis organizations used by
automated tests, so test runs never change the demonstration figures.

  reset   remove every record of the demonstration organization (sign-ins in Keycloak are kept and reused)
  seed    create the organization, its sign-ins, and the academic data (through the real API)
  verify  compare the dashboard figures with the values expected from this file's data specification

Usage: scripts/demo-data.sh reset|seed|verify|rebuild
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ENV = dict(line.strip().split("=", 1) for line in open(os.path.join(ROOT, "deploy/compose/.env")) if "=" in line and not line.startswith("#"))
API = os.environ.get("API_URL", "http://localhost:8080")
KC = os.environ.get("KEYCLOAK_URL", "http://localhost:8180")

TENANT = "0192d000-0000-7000-8000-00000000000d"
TENANT_CODE = "meridian-demo"
U = lambda n: f"0192d000-0000-7000-8000-{n:012x}"  # deterministic ids of the demo skeleton  # noqa: E731
UNITS = {  # id: (type, parent, code, name)
    U(0xd100): ("UNIVERSITY", None, "MU", "Meridian University"),
    U(0xd101): ("COLLEGE", U(0xd100), "MIE", "Meridian Institute of Engineering"),
    U(0xd102): ("COLLEGE", U(0xd100), "MCC", "Meridian College of Commerce"),
    U(0xd111): ("CAMPUS", U(0xd101), "MAIN", "MIE Main Campus"),
    U(0xd121): ("DEPARTMENT", U(0xd111), "CSE", "Computer Science and Engineering"),
    U(0xd122): ("DEPARTMENT", U(0xd111), "ECE", "Electronics and Communication"),
    U(0xd131): ("DEPARTMENT", U(0xd102), "COM", "Department of Commerce"),
}
ROLE = {"ORG_ADMIN": "01920000-0000-7000-8000-000000000002", "INSTITUTION_ADMIN": "01920000-0000-7000-8000-000000000003",
        "FACULTY": "01920000-0000-7000-8000-000000000007"}
USERS = [  # email, given, family, role code, scope unit (None = organization)
    ("demo.admin@meridian.test", "Devika", "Menon", "ORG_ADMIN", None),
    ("demo.principal@meridian.test", "Prakash", "Nair", "INSTITUTION_ADMIN", U(0xd101)),
    ("demo.hod@meridian.test", "Harini", "Subramanian", "DEPARTMENT_HEAD", U(0xd121)),
    ("demo.faculty@meridian.test", "Farhan", "Qureshi", "FACULTY", U(0xd121)),
]

# ------------------------------------------------------------------------------------------- academic specification
PROGRAMS = [  # code, name, level, owner, system, years, award
    ("BTECH", "Bachelor of Technology", "UNDERGRADUATE", U(0xd101), "SEMESTER", 4, "B.Tech"),
    ("MTECH", "Master of Technology", "POSTGRADUATE", U(0xd101), "SEMESTER", 2, "M.Tech"),
    ("BCOM", "Bachelor of Commerce", "UNDERGRADUATE", U(0xd102), "SEMESTER", 3, "B.Com"),
]
COURSES = [  # program, code, name, department, specialization
    ("BTECH", "CSE", "Computer Science and Engineering", U(0xd121), "Artificial Intelligence"),
    ("BTECH", "ECE", "Electronics and Communication Engineering", U(0xd122), None),
    ("MTECH", "DS", "Data Science", U(0xd121), "Machine Learning"),
    ("BCOM", "GEN", "Commerce (General)", U(0xd131), None),
]
SUBJECTS = [  # code, name, credits, owner, category, delivery
    ("MA101", "Engineering Mathematics I", 4, U(0xd100), "FOUNDATION", "THEORY"),
    ("MA102", "Engineering Mathematics II", 4, U(0xd100), "FOUNDATION", "THEORY"),
    ("CS101", "Programming Fundamentals", 4, U(0xd101), "CORE", "THEORY_AND_PRACTICAL"),
    ("CS201", "Data Structures", 4, U(0xd121), "CORE", "THEORY_AND_PRACTICAL"),
    ("CS202L", "Database Systems Laboratory", 2, U(0xd121), "LABORATORY", "PRACTICAL"),
    ("CS401", "Machine Learning", 3, U(0xd121), "ELECTIVE", "THEORY"),
    ("EC201", "Signals and Systems", 4, U(0xd122), "CORE", "THEORY"),
    ("CM101", "Financial Accounting", 4, U(0xd131), "CORE", "THEORY"),
    ("CM102", "Business Economics", 4, U(0xd131), "CORE", "THEORY"),
]
PREREQUISITES = [("MA102", "MA101"), ("CS201", "CS101"), ("CS401", "CS201")]
CURRICULUM = [  # course, subject, term
    ("CSE", "MA101", 1), ("CSE", "CS101", 1), ("CSE", "MA102", 2), ("CSE", "CS201", 3), ("CSE", "CS202L", 3), ("CSE", "CS401", 7),
    ("ECE", "MA101", 1), ("ECE", "MA102", 2), ("ECE", "EC201", 3), ("DS", "CS401", 1),
    ("GEN", "CM101", 1), ("GEN", "CM102", 1),
]
YEARS = [  # code, name, start, end, final status, terms [(seq, name, start, end, final status)]
    ("AY2025-26", "2025–26", "2025-07-01", "2026-06-30", "COMPLETED",
     [(1, "Odd semester", "2025-07-15", "2025-12-15", "COMPLETED"), (2, "Even semester", "2026-01-05", "2026-05-31", "COMPLETED")]),
    ("AY2026-27", "2026–27", "2026-07-01", "2027-06-30", "ACTIVE",
     [(1, "Odd semester", "2026-07-15", "2026-12-15", "ACTIVE"), (2, "Even semester", "2027-01-04", "2027-05-31", "PLANNED")]),
]
BATCHES = [  # course, code, name, year code, admission year, section, capacity
    ("CSE", "CSE-2025-A", "B.Tech CSE 2025 – Section A", "AY2026-27", 2025, "A", 60),
    ("CSE", "CSE-2026-A", "B.Tech CSE 2026 – Section A", "AY2026-27", 2026, "A", 60),
    ("CSE", "CSE-2026-B", "B.Tech CSE 2026 – Section B", "AY2026-27", 2026, "B", 60),
    ("ECE", "ECE-2026", "B.Tech ECE 2026", "AY2026-27", 2026, None, 60),
    ("GEN", "BCOM-2026", "B.Com 2026", "AY2026-27", 2026, None, 120),
]
OFFERINGS = [  # subject, batch, term code (year, seq), course term, status
    ("CS201", "CSE-2025-A", ("AY2026-27", 1), 3, "ACTIVE"), ("CS202L", "CSE-2025-A", ("AY2026-27", 1), 3, "ACTIVE"),
    ("MA101", "CSE-2026-A", ("AY2026-27", 1), 1, "ACTIVE"), ("CS101", "CSE-2026-A", ("AY2026-27", 1), 1, "ACTIVE"),
    ("MA101", "CSE-2026-B", ("AY2026-27", 1), 1, "ACTIVE"), ("CS101", "CSE-2026-B", ("AY2026-27", 1), 1, "PLANNED"),
    ("MA101", "ECE-2026", ("AY2026-27", 1), 1, "ACTIVE"), ("CM101", "BCOM-2026", ("AY2026-27", 1), 1, "ACTIVE"),
    ("CM102", "BCOM-2026", ("AY2026-27", 1), 1, "PLANNED"),
]
STAFF = [  # staff no, given, family, designation, unit
    ("MIE-F001", "Kavita", "Joshi", "Professor", U(0xd121)), ("MIE-F002", "Sanjay", "Kulkarni", "Associate Professor", U(0xd121)),
    ("MIE-F003", "Meenakshi", "Iyer", "Assistant Professor", U(0xd121)), ("MIE-F004", "Arjun", "Reddy", "Assistant Professor", U(0xd122)),
    ("MIE-F005", "Lakshmi", "Pillai", "Professor", U(0xd101)), ("MCC-F001", "Rahul", "Banerjee", "Associate Professor", U(0xd131)),
]
FACULTY = [  # (subject, batch), staff no, primary
    (("CS201", "CSE-2025-A"), "MIE-F001", True), (("CS201", "CSE-2025-A"), "MIE-F003", False), (("CS202L", "CSE-2025-A"), "MIE-F003", True),
    (("MA101", "CSE-2026-A"), "MIE-F005", True), (("CS101", "CSE-2026-A"), "MIE-F002", True), (("MA101", "CSE-2026-B"), "MIE-F005", True),
    (("MA101", "ECE-2026"), "MIE-F004", True), (("CM101", "BCOM-2026"), "MCC-F001", True),
]


def expected_academic_kpis():
    active_offering = [o for o in OFFERINGS if o[4] in ("ACTIVE", "PLANNED")]
    with_faculty = {f[0] for f in FACULTY}
    return {
        "programs": len(PROGRAMS), "courses": len(COURSES), "subjects": len(SUBJECTS),
        "activeAcademicYears": sum(1 for y in YEARS if y[4] == "ACTIVE"),
        "activeTerms": sum(1 for y in YEARS for t in y[5] if t[4] == "ACTIVE"),
        "activeBatches": len(BATCHES), "offerings": len(active_offering),
        "offeringsWithoutFaculty": sum(1 for o in active_offering if (o[0], o[1]) not in with_faculty),
        "facultyAssignments": len(FACULTY), "facultyTeaching": len({f[1] for f in FACULTY}),
    }


# ------------------------------------------------------------------------------------------- student specification
# Entirely fictitious people. Final status drives the lifecycle walk: PROSPECTIVE (profile only), ADMITTED (admission
# confirmed), ENROLLED, ACTIVE, SUSPENDED, WITHDRAWN, GRADUATED. "recent" admissions are dated within the last 30 days.
NUMBERING = [(U(0xd101), {"mode": "GENERATED", "template": "{INST}{YYYY}{SEQ:4}", "registrationPattern": "AAA/9999/*"}),
             (U(0xd102), {"mode": "MANUAL", "manualPattern": "MCC-9999"})]
STUDENTS = [  # first, last, college, course, batch, status, admission year, recent admission, guardian
    ("Aarav", "Kapoor", U(0xd101), "CSE", "CSE-2025-A", "ACTIVE", 2025, False, ("Neha Kapoor", "MOTHER")),
    ("Ishita", "Menon", U(0xd101), "CSE", "CSE-2025-A", "ACTIVE", 2025, False, ("Vinod Menon", "FATHER")),
    ("Kabir", "Sethi", U(0xd101), "CSE", "CSE-2025-A", "ACTIVE", 2025, False, None),
    ("Diya", "Bhatt", U(0xd101), "CSE", "CSE-2025-A", "SUSPENDED", 2025, False, ("Rekha Bhatt", "MOTHER")),
    ("Vihaan", "Joshi", U(0xd101), "CSE", "CSE-2025-A", "WITHDRAWN", 2025, False, None),
    ("Ananya", "Iyer", U(0xd101), "CSE", "CSE-2026-A", "ACTIVE", 2026, True, ("Suresh Iyer", "FATHER")),
    ("Reyansh", "Gupta", U(0xd101), "CSE", "CSE-2026-A", "ACTIVE", 2026, True, None),
    ("Saanvi", "Nair", U(0xd101), "CSE", "CSE-2026-A", "ENROLLED", 2026, True, ("Lakshmi Nair", "MOTHER")),
    ("Arjun", "Patil", U(0xd101), "CSE", "CSE-2026-B", "ACTIVE", 2026, True, None),
    ("Myra", "Desai", U(0xd101), "CSE", "CSE-2026-B", "ACTIVE", 2026, True, None),
    ("Vivaan", "Rao", U(0xd101), "CSE", "CSE-2026-B", "ENROLLED", 2026, True, None),
    ("Aditi", "Kulkarni", U(0xd101), "ECE", "ECE-2026", "ACTIVE", 2026, True, ("Mohan Kulkarni", "FATHER")),
    ("Rohan", "Shetty", U(0xd101), "ECE", "ECE-2026", "ACTIVE", 2026, True, None),
    ("Tara", "Mishra", U(0xd101), "ECE", "ECE-2026", "ADMITTED", 2026, True, None),
    ("Kiara", "Fernandes", U(0xd102), "GEN", "BCOM-2026", "ACTIVE", 2026, True, ("Joanna Fernandes", "MOTHER")),
    ("Yash", "Agarwal", U(0xd102), "GEN", "BCOM-2026", "ACTIVE", 2026, True, None),
    ("Meher", "Chawla", U(0xd102), "GEN", "BCOM-2026", "ACTIVE", 2026, True, None),
    ("Dev", "Malhotra", U(0xd102), "GEN", "BCOM-2026", "ENROLLED", 2026, True, None),
    ("Nisha", "Pillai", U(0xd101), "CSE", None, "PROSPECTIVE", 2026, True, None),
    ("Omkar", "Jadhav", U(0xd101), "ECE", None, "PROSPECTIVE", 2026, True, None),
]
PROGRAM_OF_COURSE = {c[1]: c[0] for c in COURSES}


def expected_student_kpis():
    import import_samples as samples
    imported = [(first, last, U(0xd101), None, batch, status.upper(), 2026, True, g) for first, last, _, batch, status, g in samples.MIE_INTAKE]
    everyone = STUDENTS + imported
    statuses = [st[5] for st in everyone]
    current = [st for st in everyone if st[5] in ("ENROLLED", "ACTIVE", "SUSPENDED")]
    by_batch = {}
    for st in current:
        by_batch[st[4]] = by_batch.get(st[4], 0) + 1
    return {
        "total": len(everyone), "active": sum(1 for x in statuses if x in ("ENROLLED", "ACTIVE")),
        "prospective": statuses.count("PROSPECTIVE"), "suspended": statuses.count("SUSPENDED"), "withdrawn": statuses.count("WITHDRAWN"),
        "graduated": statuses.count("GRADUATED"), "newlyAdmitted": sum(1 for st in everyone if st[7] and st[5] != "PROSPECTIVE"),
        "byBatch": dict(sorted(by_batch.items())),
    }


# --------------------------------------------------------------------------------------------------------- plumbing
def psql(sql):
    r = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "ivorovi", "-v", "ON_ERROR_STOP=1",
                        "-qAt", "-c", "SET search_path = ivorovi; " + sql], cwd=os.path.join(ROOT, "deploy/compose"), capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("psql failed: " + r.stderr.strip())
    return r.stdout.strip()


def http(method, url, body=None, token=None, headers=None, form=None):
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
            return e.code, (json.loads(raw) if raw else None)
    sys.exit("rate limited too long: " + url)


def ok(result, *codes):
    status, body = result
    if status not in codes:
        sys.exit(f"unexpected {status}: {body}")
    return body


def guard():
    status, health = http("GET", f"{API}/actuator/health")
    if status != 200:
        sys.exit("API is not running on " + API)
    if not API.startswith("http://localhost") and not API.startswith("http://127.0.0.1"):
        sys.exit("refusing to run against a non-local API")


def provisioner_token():
    return ok(http("POST", f"{KC}/realms/ivorovi/protocol/openid-connect/token",
                   form={"grant_type": "client_credentials", "client_id": "ivorovi-provisioner", "client_secret": ENV["KEYCLOAK_PROVISIONER_SECRET"]}), 200)["access_token"]


def user_token(email):
    return ok(http("POST", f"{KC}/realms/ivorovi/protocol/openid-connect/token", form={"grant_type": "password", "client_id": "ivorovi-dev-cli",
              "scope": "openid", "username": email, "password": ENV["DEV_USER_PASSWORD"]}), 200)["access_token"]


def keycloak_user(token, email, given, family):
    """Existing or new Keycloak user bound to the demo organization; returns its subject (id)."""
    found = ok(http("GET", f"{KC}/admin/realms/ivorovi/users?email={urllib.parse.quote(email)}&exact=true", token=token), 200)
    if not found:
        ok(http("POST", f"{KC}/admin/realms/ivorovi/users", {"username": email, "email": email, "emailVerified": True, "enabled": True,
              "firstName": given, "lastName": family, "attributes": {"tenant_id": [TENANT]},
              "credentials": [{"type": "password", "value": ENV["DEV_USER_PASSWORD"], "temporary": False}]}, token=token), 201)
        found = ok(http("GET", f"{KC}/admin/realms/ivorovi/users?email={urllib.parse.quote(email)}&exact=true", token=token), 200)
    return found[0]["id"]


# ------------------------------------------------------------------------------------------------------------ steps
def reset():
    guard()
    tables = psql("SELECT string_agg(table_name, ',') FROM information_schema.columns WHERE table_schema = 'ivorovi' AND column_name = 'tenant_id'").split(",")
    stmts = ["SET session_replication_role = replica"] + [f"DELETE FROM {t} WHERE tenant_id = '{TENANT}'" for t in tables if t] + \
            [f"DELETE FROM tenant WHERE id = '{TENANT}' AND code = '{TENANT_CODE}'", "SET session_replication_role = origin"]
    psql("; ".join(stmts))
    subprocess.run(["docker", "compose", "exec", "-T", "valkey", "sh", "-c", "valkey-cli -a \"$REDIS_PASSWORD\" --no-auth-warning --scan --pattern '*" + TENANT + "*' "
                    "| xargs -r valkey-cli -a \"$REDIS_PASSWORD\" --no-auth-warning del"], cwd=os.path.join(ROOT, "deploy/compose"), capture_output=True)
    print("Demonstration organization removed (Keycloak sign-ins kept).")


def skeleton():
    """Organization, hierarchy and sign-ins: the only part written directly (there is no tenant-provisioning API yet)."""
    if psql(f"SELECT count(*) FROM tenant WHERE id = '{TENANT}'") != "0":
        return
    rows = [f"INSERT INTO tenant (id, code, name, status, branding) VALUES ('{TENANT}', '{TENANT_CODE}', 'Meridian Education Trust (demo)', 'ACTIVE', "
            "'{\"displayName\":\"Meridian Education Trust\",\"primaryColor\":\"#7c3aed\"}')"]
    paths = {}
    for uid, (typ, parent, code, name) in UNITS.items():
        label = uid.replace("-", "")
        paths[uid] = (paths[parent] + "." if parent else "") + label
        rows.append(f"INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES ('{uid}', '{TENANT}', "
                    f"{'NULL' if parent is None else repr(parent)}, '{typ}', '{code}', '{name}', '{paths[uid]}')")
    kc = provisioner_token()
    for i, (email, given, family, role, scope) in enumerate(USERS):
        subject = keycloak_user(kc, email, given, family)
        uid = U(0xd1000 + i)
        role_id = ROLE.get(role) or psql(f"SELECT id FROM role WHERE code = '{role}' AND tenant_id IS NULL")
        rows.append(f"INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name, family_name, status) VALUES "
                    f"('{uid}', '{TENANT}', '{subject}', '{email}', '{given} {family}', '{given}', '{family}', 'ACTIVE')")
        rows.append(f"INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary) VALUES ('{U(0xd2000 + i)}', '{TENANT}', '{uid}', "
                    f"{'NULL' if scope is None else repr(scope)}, true)")
        rows.append(f"INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES ('{U(0xd3000 + i)}', '{TENANT}', '{uid}', "
                    f"'{role_id}', '{'TENANT' if scope is None else 'ORG_UNIT'}', '{scope or TENANT}')")
    psql("; ".join(rows))


def seed():
    guard()
    skeleton()
    t = user_token(USERS[0][0])
    key = lambda: {"Idempotency-Key": "demo-" + uuid.uuid4().hex}  # noqa: E731

    def get_items(path):
        return ok(http("GET", f"{API}{path}{'&' if '?' in path else '?'}size=100", token=t), 200)["items"]

    def set_status(path, item_id, target, reason=None):
        cur = ok(http("GET", f"{API}{path}/{item_id}", token=t), 200)
        obj = cur.get("year", cur.get("subject", cur))
        if obj["status"] != target:
            ok(http("POST", f"{API}{path}/{item_id}/status", {"status": target, "version": obj["version"], **({"reason": reason} if reason else {})}, token=t), 200)

    if get_items("/api/v1/academics/programs"):
        print("Academic data already present; run 'reset' first to rebuild.")
        return
    prog = {}
    for code, name, level, owner, system, years, award in PROGRAMS:
        prog[code] = ok(http("POST", f"{API}/api/v1/academics/programs", {"code": code, "name": name, "qualificationLevel": level, "ownerOrgUnitId": owner,
                        "termSystem": system, "durationYears": years, "awardedQualification": award}, token=t, headers=key()), 201)["id"]
    course = {}
    for p, code, name, dept, spec in COURSES:
        course[code] = ok(http("POST", f"{API}/api/v1/academics/courses", {"code": code, "name": name, "programId": prog[p], "departmentId": dept,
                          **({"specialization": spec} if spec else {})}, token=t, headers=key()), 201)["id"]
    subj = {}
    for code, name, credits, owner, cat, delivery in SUBJECTS:
        subj[code] = ok(http("POST", f"{API}/api/v1/academics/subjects", {"code": code, "name": name, "credits": credits, "delivery": delivery,
                        "category": cat, "ownerOrgUnitId": owner}, token=t, headers=key()), 201)["subject"]["id"]
    for s, p in PREREQUISITES:
        ok(http("POST", f"{API}/api/v1/academics/subjects/{subj[s]}/prerequisites", {"prerequisiteId": subj[p]}, token=t), 200)
    for c, s, term in CURRICULUM:
        ok(http("POST", f"{API}/api/v1/academics/courses/{course[c]}/curriculum", {"subjectId": subj[s], "termNumber": term}, token=t, headers=key()), 200)
    year, term_ids = {}, {}
    for code, name, start, end, status, terms in YEARS:
        year[code] = ok(http("POST", f"{API}/api/v1/academics/academic-years", {"code": code, "name": name, "startDate": start, "endDate": end},
                        token=t, headers=key()), 201)["year"]["id"]
        for seq, tname, ts, te, _ in terms:
            term_ids[(code, seq)] = ok(http("POST", f"{API}/api/v1/academics/academic-years/{year[code]}/terms", {"sequence": seq, "name": tname,
                                       "startDate": ts, "endDate": te}, token=t, headers=key()), 201)["id"]
    for code, _, _, _, status, terms in YEARS:  # walk the lifecycle forward to the final status
        set_status("/api/v1/academics/academic-years", year[code], "ACTIVE")
        for seq, _, _, _, tstatus in terms:
            if tstatus in ("ACTIVE", "COMPLETED"):
                set_status("/api/v1/academics/academic-terms", term_ids[(code, seq)], "ACTIVE")
            if tstatus == "COMPLETED":
                set_status("/api/v1/academics/academic-terms", term_ids[(code, seq)], "COMPLETED")
        if status == "COMPLETED":
            set_status("/api/v1/academics/academic-years", year[code], "COMPLETED")
    batch = {}
    for c, code, name, ycode, adm, section, cap in BATCHES:
        batch[code] = ok(http("POST", f"{API}/api/v1/academics/batches", {"code": code, "name": name, "courseId": course[c], "academicYearId": year[ycode],
                         "admissionYear": adm, "capacity": cap, **({"section": section} if section else {})}, token=t, headers=key()), 201)["id"]
    offering = {}
    for s, b, tk, n, status in OFFERINGS:
        offering[(s, b)] = ok(http("POST", f"{API}/api/v1/academics/subject-offerings", {"subjectId": subj[s], "batchId": batch[b], "termId": term_ids[tk],
                              "termNumber": n}, token=t, headers=key()), 201)["id"]
        if status == "ACTIVE":
            set_status("/api/v1/academics/subject-offerings", offering[(s, b)], "ACTIVE")
    staff = {}
    for no, given, family, designation, unit in STAFF:
        r = ok(http("POST", f"{API}/api/v1/staff", {"staffNo": no, "givenName": given, "familyName": family, "category": "FACULTY",
               "designation": designation, "orgUnitId": unit}, token=t, headers=key()), 201)
        staff[no] = r.get("id") or r["staff"]["id"]
    for o, no, primary in FACULTY:
        ok(http("POST", f"{API}/api/v1/academics/subject-offerings/{offering[o]}/faculty", {"staffProfileId": staff[no], "primary": primary},
                token=t, headers=key()), 200)
    global prog_of
    prog_of = {c: prog[PROGRAM_OF_COURSE[c]] for c in course}
    seed_students(t, key, course, batch, year)
    seed_imports(t)
    seed_integrations(t)
    print("Demonstration data created.")


def seed_students(t, key, course, batch, year):
    import datetime
    for unit, rule in NUMBERING:
        ok(http("PUT", f"{API}/api/v1/student-settings/numbering-rules/{unit}", {**rule, "version": -1}, token=t), 200)
    today = datetime.date.today()
    mcc_no = 0
    for i, (first, last, college, c, b, status, adm_year, recent, guardian) in enumerate(STUDENTS):
        body = {"institutionId": college, "firstName": first, "lastName": last, "admissionYear": adm_year,
                "institutionalEmail": f"{first.lower()}.{last.lower()}@students.meridian.test",
                "personal": {"dateOfBirth": f"{adm_year - 18}-{(i % 12) + 1:02d}-{(i % 27) + 1:02d}", "gender": "UNDISCLOSED", "nationality": "Indian",
                             "mobile": f"+91 90000 {10000 + i:05d}", "currentAddress": f"{10 + i} Demo Street, Meridian City"}}
        if college == U(0xd102):
            mcc_no += 1
            body["studentNo"] = f"MCC-{1000 + mcc_no}"
        d = ok(http("POST", f"{API}/api/v1/students", body, token=t, headers=key()), 201)
        sid = d["student"]["id"]
        if guardian:
            ok(http("POST", f"{API}/api/v1/students/{sid}/guardians", {"name": guardian[0], "relationship": guardian[1], "mobile": f"+91 90001 {10000 + i:05d}",
                    "emergency": True}, token=t, headers=key()), 200)
        if status == "PROSPECTIVE":
            continue
        adm_date = str(today - datetime.timedelta(days=5 + i % 20)) if recent else f"{adm_year}-07-01"
        ok(http("POST", f"{API}/api/v1/students/{sid}/admissions", {"admissionNo": f"ADM-{adm_year}-{i + 1:03d}", "admissionDate": adm_date, "admissionType": "REGULAR",
                "entryAcademicYearId": year["AY2026-27"], "programId": prog_of[c], "courseId": course[c], "entryTermNumber": 1, "status": "CONFIRMED"},
                token=t, headers=key()), 200)
        if status == "ADMITTED":
            continue
        term = 3 if adm_year == 2025 else 1
        ok(http("POST", f"{API}/api/v1/students/{sid}/enrolments", {"type": "INITIAL", "batchId": batch[b], "termNumber": term}, token=t, headers=key()), 200)
        walk = {"ENROLLED": [], "ACTIVE": ["ACTIVE"], "SUSPENDED": ["ACTIVE", "SUSPENDED"], "WITHDRAWN": ["ACTIVE", "WITHDRAWN"], "GRADUATED": ["ACTIVE", "GRADUATED"]}[status]
        reasons = {"ACTIVE": "Classes started", "SUSPENDED": "Fee hold (demonstration)", "WITHDRAWN": "Left for another institution (demonstration)",
                   "GRADUATED": "Completed the program"}
        for target in walk:
            v = ok(http("GET", f"{API}/api/v1/students/{sid}", token=t), 200)["version"]
            ok(http("POST", f"{API}/api/v1/students/{sid}/status", {"status": target, "reason": reasons[target], "version": v}, token=t), 200)


def import_upload(t, name, content):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: text/csv\r\n\r\n").encode() + content \
        + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{API}/api/v1/student-imports?institutionId={U(0xd101)}&importKey=demo-{name.replace('.', '-')}-{uuid.uuid4().hex[:8]}",
                                 data=body, method="POST", headers={"Authorization": f"Bearer {t}", "Content-Type": f"multipart/form-data; boundary={boundary}"})
    for _ in range(30):
        try:
            with urllib.request.urlopen(req) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code != 429:
                sys.exit(f"import upload failed: {e.code} {e.read()[:300]!r}")
            time.sleep(2)
    sys.exit("import upload rate limited too long")


def import_wait(t, job_id, statuses):
    for _ in range(300):
        j = ok(http("GET", f"{API}/api/v1/student-imports/{job_id}", token=t), 200)
        if j["status"] in statuses:
            return j
        time.sleep(1)
    sys.exit(f"import {job_id} did not reach {statuses}")


def seed_imports(t):
    """1.6: one completed import (the MIE intake) and one validated file with problems awaiting decisions, through the real API."""
    import datetime
    import import_samples as samples
    adm = str(datetime.date.today() - datetime.timedelta(days=12))
    for name, rows, confirm in [("mie-2026-intake.csv", samples.mie_rows(adm), True), ("mie-late-applications.csv", samples.issue_rows(adm), False)]:
        job = import_upload(t, name, samples.to_csv(rows))
        cols = [{"column": m["column"], "field": m["field"]} for m in job["mapping"] if m.get("field")]
        ok(http("PUT", f"{API}/api/v1/student-imports/{job['id']}/mapping", {"columns": cols, "version": job["version"]}, token=t), 200)
        job = import_wait(t, job["id"], {"READY"})
        if confirm:
            c = job["counts"]
            ok(http("POST", f"{API}/api/v1/student-imports/{job['id']}/confirm", {"mode": "ALL_OR_NOTHING", "version": job["version"],
                    "expectedRows": c["toCreate"] + c["toUpdate"], "acknowledged": True}, token=t), 200)
            job = import_wait(t, job["id"], {"COMPLETED", "PARTIALLY_COMPLETED", "FAILED"})
            if job["status"] != "COMPLETED":
                sys.exit(f"demonstration import ended {job['status']}: {job.get('failureMessage')}")


def seed_integrations(t):
    """Increment 1.7: integrations that work locally (Mailpit relay, the API's own health endpoint) and a read-only API client.
    The API key is printed once and not stored anywhere by this script."""
    smtp = ok(http("POST", f"{API}/api/v1/integrations", {"kind": "EMAIL", "adapter": "smtp", "name": "Campus mail relay (Mailpit)",
              "description": "Local development relay", "config": {"host": "localhost", "port": "1025", "security": "NONE"}}, token=t), 201)
    rest = ok(http("POST", f"{API}/api/v1/integrations", {"kind": "SIS_ERP", "adapter": "generic-rest", "name": "Student information system (sandbox)",
              "description": "Connectivity check against a local endpoint; no data is exchanged",
              "config": {"baseUrl": f"{API}/actuator", "healthPath": "/health", "syncPath": "/health", "authType": "NONE"}}, token=t), 201)
    for i in (smtp, rest):
        ok(http("PUT", f"{API}/api/v1/integrations/{i['id']}/enabled", {"enabled": True}, token=t), 200)
        ok(http("POST", f"{API}/api/v1/integrations/{i['id']}/test", None, token=t), 200)
    ok(http("POST", f"{API}/api/v1/integrations/{rest['id']}/sync", None, token=t), 200)
    client = ok(http("POST", f"{API}/api/v1/api-clients", {"name": "Timetable service (read-only)", "description": "Reads the academic structure nightly",
                "scopes": ["academics.read", "org_unit.read"]}, token=t), 201)
    print(f"  API client created; its key was shown once and discarded (key id {client['keyId']}).")


def verify():
    guard()
    t = user_token(USERS[0][0])
    actual = ok(http("GET", f"{API}/api/v1/academics/overview", token=t), 200)
    expected = expected_academic_kpis()
    bad = {k: (v, actual.get(k)) for k, v in expected.items() if actual.get(k) != v}
    for k, v in expected.items():
        print(f"  {'OK  ' if k not in bad else 'DIFF'} {k:24} expected {v:4}  actual {actual.get(k)}")
    students = ok(http("GET", f"{API}/api/v1/students/overview", token=t), 200)
    exp = expected_student_kpis()
    actual_batches = dict(sorted((g["label"], g["count"]) for g in students["byBatch"]))
    for k, v in exp.items():
        got = actual_batches if k == "byBatch" else students.get(k)
        same = got == v
        if not same:
            bad[k] = (v, got)
        print(f"  {'OK  ' if same else 'DIFF'} students.{k:16} expected {v}  actual {got}")
    if bad:
        sys.exit("Dashboard figures differ from the demonstration specification.")
    print("Demonstration figures match the specification.")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"reset": reset, "seed": seed, "verify": verify, "rebuild": lambda: (reset(), seed(), verify())}.get(cmd, lambda: sys.exit(__doc__))()
