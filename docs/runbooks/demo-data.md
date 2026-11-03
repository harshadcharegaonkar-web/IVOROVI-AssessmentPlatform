# Demonstration dataset (local development)

A repeatable, entirely synthetic demonstration organization for reviews and screenshots, kept apart from test data.

| | Demonstration | Automated tests |
|---|---|---|
| Organization | **Meridian Education Trust** (`meridian-demo`) | Aurora Education Group, Borealis University (dev fixtures) and fresh per-test organizations (integration tests) |
| Sign-ins | `demo.admin@`, `demo.principal@`, `demo.hod@`, `demo.faculty@meridian.test` (password: `DEV_USER_PASSWORD` from `deploy/compose/.env`) | `*@aurora.test`, `*@borealis.test` |
| Created by | `scripts/demo-data.sh` through the real API (the organization skeleton is inserted directly; there is no tenant-provisioning API yet) | Playwright, smoke test, JUnit |

Tests never sign in to Meridian, and every record is tenant-isolated (RLS), so test runs cannot change the
demonstration figures.

## Commands

```bash
scripts/demo-data.sh rebuild   # reset + seed + verify
scripts/demo-data.sh reset     # remove every Meridian record (keeps the Keycloak sign-ins)
scripts/demo-data.sh seed      # create the organization, sign-ins and data
scripts/demo-data.sh verify    # compare dashboard figures with the expected values
```

Requirements: the local stack is running (`docker compose up -d`, API on `localhost:8080`, Keycloak on `localhost:8180`).
The script refuses to run against a non-local API, and `reset` only deletes rows of the `meridian-demo` organization.

## What it contains

Defined as data at the top of `scripts/demo/demo_data.py`:

* Hierarchy: Meridian University → Meridian Institute of Engineering (MIE Main Campus → CSE, ECE) and Meridian
  College of Commerce (Commerce).
* Academic structure: 3 programs, 4 courses, 9 subjects with prerequisites and curricula, academic years 2025–26
  (completed) and 2026–27 (active, odd semester active), 5 batches, 9 offerings, 6 faculty staff, 8 faculty assignments.
* Students (1.5): numbering rules (Meridian Institute of Engineering generates `{INST}{YYYY}{SEQ:4}`; the College of
  Commerce enters `MCC-9999`), 20 synthetic students (prospective, admitted, enrolled, active, suspended and withdrawn), admissions, enrolments and guardians.

## Predictable figures

`verify` computes the expected dashboard figures from the specification itself (for example, offerings without faculty
= planned or active offerings that have no faculty entry) and fails if any differs. Current expectations:

| KPI | Expected |
|---|---|
| Active programs / courses / subjects | 3 / 4 / 9 |
| Active academic years / terms | 1 / 1 |
| Active batches | 5 |
| Subject offerings (without faculty) | 9 (2) |
| Faculty assignments (staff teaching) | 8 (6) |
| Students total / active / prospective | 20 / 15 / 2 |
| Newly admitted (30 days) / suspended / withdrawn | 13 / 1 / 1 |
| Current enrolments by batch | BCOM-2026 4, CSE-2025-A 4, CSE-2026-A 3, CSE-2026-B 3, ECE-2026 2 |

## Safety

* Synthetic names only; no real personal or student information. Do not import real data into the demonstration
  organization.
* Development only: the script needs local Docker access to PostgreSQL and is not shipped with the application.
