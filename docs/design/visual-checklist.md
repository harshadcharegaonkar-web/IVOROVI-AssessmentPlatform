# Visual comparison checklist

Every Phase 1 screen is checked against [design-system.md](design-system.md) and references R1/R2 **before** it
is marked complete. The result is recorded per screen with screenshots at 1440 × 900, 1024 × 768 and 390 × 844
(captured by Playwright from the real stack with real data).

## A. Shell (checked once, re-checked when changed)

| # | Check |
|---|---|
| A1 | Navy sidebar `#17243A`, 256 px expanded / 72 px collapsed; logo tile + "IVOROVI / Assessment Platform" |
| A2 | Nav groups and items match §3 of the design system; only permitted items are visible; empty groups hidden |
| A3 | Active item: tinted pill, white label, blue indicator; hover state subtle; focus ring visible on navy |
| A4 | Collapse toggle works; collapsed rail shows tooltips; preference persists per browser |
| A5 | White top bar 64 px: breadcrumbs, institution selector (only when > 1 option), search, bell with count, account menu with sign-out |
| A6 | Tablet: navy top bar + rail; mobile: navy top bar + drawer; no horizontal page scroll at 390 px |
| A7 | Workspace background `#F4F6FA`; page padding 32/24/16 px |

## B. Per-screen checklist (copy for each screen)

| # | Check | Pass |
|---|---|---|
| B1 | Page header: title 24/600, description muted (`#475569` on page background), actions right-aligned, primary action blue | ☐ |
| B2 | Breadcrumbs reflect location; last crumb not a link | ☐ |
| B3 | Cards: white, 12 px radius, 1 px `#E2E8F0` border, `shadow-xs`, 24 px padding | ☐ |
| B4 | Typography uses only the scale in §2.2; figures tabular | ☐ |
| B5 | Tables: header style, 52 px rows, hover, right-aligned numbers, row actions in a menu, pagination footer | ☐ |
| B6 | Search and filters in a filter bar above the table; filters reflected in the URL | ☐ |
| B7 | States present and styled consistently: loading skeleton, empty (with next-step action), error (with retry and reference), success toast | ☐ |
| B8 | Forms: labels above controls, 40 px controls, inline validation messages, required markers, disabled states | ☐ |
| B9 | Modals and confirm dialogs use the standard component; destructive actions red and confirmed | ☐ |
| B10 | Status badges use the semantic tokens (text colours meet AA) | ☐ |
| B11 | Icons from Lucide only, consistent sizes (20 nav / 16 inline) | ☐ |
| B12 | No gradients (except chart area fill), no decorative animation, no oversized type | ☐ |
| B13 | Responsive at all three viewports: no overflow, tables usable, actions reachable | ☐ |
| B14 | Accessibility: axe scan has no serious or critical issues; keyboard-only path through the main workflow; visible focus | ☐ |
| B15 | Every displayed number and list comes from the API (verified against DB in the E2E scenario) | ☐ |
| B16 | Side-by-side review with R1/R2 anatomy (KPI card, chart card, list, table) signed off | ☐ |

## C. Screen register

| # | Screen | Inc. | Checklist | Screenshots | Reviewer sign-off |
|---|---|---|---|---|---|
| 1 | Dashboard | 1.1 (org KPIs) → 1.6 (full) | 🟨 org KPIs (configured labels) + organization distribution in 1.1; people/academic KPIs later | ✅ 1.0, 1.1 | ☐ 1.1 review |
| 2 | Organization hierarchy | 1.1 | ✅ A1–A7, B14 (axe) | ✅ 3 viewports | ☐ |
| 3 | University / College listings and unit details | 1.1 | ✅ listing, detail (overview, sub-units, features, history), forms, dialogs | ✅ | ☐ |
| 4 | Campus management | 1.1 | ✅ (shared listing/detail) | ✅ | ☐ |
| 5 | Department management | 1.1 | ✅ (shared listing/detail; axe) | ✅ | ☐ |
| 6 | Academic year management (years, year detail with terms; semester management) | 1.4 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 7 | Program management (list, details, create/edit) | 1.4 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 8 | Course management (list, details with curriculum by term, create/edit) | 1.4 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 9 | Subject management (list, details with prerequisites, create/edit) | 1.4 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 10 | Batch management (list, details with offerings, create/edit) | 1.4 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 11 | User management (list, details, create/invite, edit, status, access overview) | 1.2 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 12 | Staff management (list, details, form, link to user; faculty teaching attributes later) | 1.2 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 13 | Student management (dashboard, list with search and filters, create/edit, lifecycle) | 1.5 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 14 | Student profile (overview, personal, academic: admission/enrolment/history, guardians, documents, history, account) | 1.5 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 15 | Bulk student import | later | ☐ | ☐ | ☐ |
| + | Student settings (numbering rules, option lists) | 1.5 | ✅ (axe) | ✅ 3 viewports | ☐ |
| 16 | Role management (list, details, create/edit/clone, deactivate, archive) | 1.3 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 17 | Permission catalogue, permission configurator, role comparison | 1.3 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |
| 18 | Access management (role assignment view and overview, organizational assignments, effective access) | 1.2 / 1.3 | ✅ | ✅ | ☐ |
| 19 | Feature settings | 1.0 (restyle) / 1.1 (history, institution inheritance) | ✅ 1.0 restyle | ✅ | ✅ 1.0 approved 2026-11-02 |
| 20 | Audit logs | 1.0 (restyle) | ✅ (B5 fixed: actor names) | ✅ | ✅ approved 2026-11-02 |
| 21 | My Access | 1.0 (restyle) | ✅ | ✅ | ✅ approved 2026-11-02 |
| + | Invitation management; public invitation acceptance | 1.2 | ✅ (axe) | ✅ 3 viewports | ☐ |
| + | System settings (academic terminology, overlapping years in 1.4) | 1.1 / 1.4 | ✅ (axe) | ✅ 3 viewports | ☐ |
| + | Academic overview, subject offerings, faculty assignment, academic structure tree | 1.4 | ✅ A1–A7, axe | ✅ 3 viewports | ☐ |

## D. Results: increment 1.0 (2026-11-02), approved by the product owner as the visual baseline

Shell checks A1–A7: **pass** at 1440 × 900, 1024 × 768, 390 × 844 (and 360 px overflow check). Evidence:
`docs/design/evidence/1.0/`. Automated in Playwright: A3 (rail accessible names), A4 (collapse persists), A6
(no horizontal scroll on every screen at mobile width; tablet rail width and navy top bar colour), B14 (axe on the
dashboard). Defects found by this review and fixed are listed in `docs/phase-1/increment-1.0.md`.

## E. Results: increment 1.1 (2026-11-02), awaiting review

Shell checks A1–A7 pass on all new screens at 1440, 1024 and 390 px. Evidence: `docs/design/evidence/1.1/`
(29 screenshots). Playwright asserts no horizontal scrolling on every new screen at tablet and mobile widths, and
runs axe (WCAG 2.1 AA, no serious/critical violations) on the hierarchy, a listing, a unit detail and System
Settings. Defects found and fixed: KI-29, KI-30. Pixel-diff automation (below) was **not** delivered (KI-26).

## F. Results: increment 1.2 (2026-11-03), awaiting review

All new screens follow the approved baseline at 1440×900, 1024×768 and 390×844 (`docs/design/evidence/1.2/`,
41 screenshots). Playwright asserts no horizontal scrolling on every new screen at tablet and mobile widths and
runs axe on the users list, access overview, invitations, staff details and the public invitation page. Found
and fixed during review: clipped status column on phones (moved under the name), duplicate assignment line in
the access summary (now labelled "via staff profile"), contrast of a label on the invitation page.

## G. Results: increment 1.3 (2026-11-03), awaiting review

New Access screens follow the baseline at 1440×900, 1024×768 and 390×844 (`docs/design/evidence/1.3/`, 33
screenshots). Overflow checks at tablet and mobile on every new screen; axe on list, catalogue, editor, detail,
comparison and overview. Review changes: planned permission areas collapsed in the editor so a non-specialist sees
only what can be granted today; organization-only permissions disabled with the reason for unit-limited roles.

## H. Results: increment 1.4 (2026-11-03), awaiting review

The 14 academic screens follow the baseline at 1440×900, 1024×768 and 390×844 (`docs/design/evidence/1.4/`, 55
screenshots including the term, offering and faculty dialogs). Overflow checks at tablet and mobile on every academic
list and editor; axe on overview, every list, program/subject editors, course curriculum, year detail, faculty assignment
and the structure tree. Configured terminology is used in navigation, headings, filters and empty states.

## I. Results: increment 1.5 (2026-11-03), accepted

The 12 student screens follow the baseline at 1440×900, 1024×768 and 390×844 (`docs/design/evidence/1.5/`). The profile
is split into Overview, Personal information, Academic, Guardians, Documents and History tabs; tabs the viewer may not
see (guardians, documents) are not shown at all. Quarantined documents show a warning and a disabled download with an
accessible explanation. Overflow checks at tablet and mobile on every student screen and tab; axe on dashboard, list,
editor, every profile tab, dialogs and settings.

## J. Results: increment 1.6 (2026-11-03), awaiting review

The 12 import screens (dashboard, upload, column mapping, validation, duplicate resolution, preview, confirmation,
progress, result, error report, history, templates and rules) follow the baseline at 1440×900, 1024×768 and 390×844
(`docs/design/evidence/1.6/`, Meridian demonstration organization, synthetic data). A step indicator shows where an
import stands; the current step uses the primary colour with its contrast colour, inactive steps use the strong muted
text (contrast ≥ 4.5:1). Wide tables (mapped-value preview) scroll inside a keyboard-focusable region; row tables keep
the row identity column on mobile and hide secondary columns. Import mode is a radio group; confirmation needs an
explicit checkbox. axe (WCAG 2.1 AA): no serious or critical violation on every step; no horizontal scrolling at tablet
and mobile (Playwright `imports.spec.ts`).

Automation (re-planned): Playwright captures the three viewports for every screen into `frontend/apps/web/e2e/__screenshots__`
and runs `toHaveScreenshot` against approved baselines in CI (built into Playwright; no new tool). Baselines are
updated only through a reviewed PR.


## Increment 1.7 screens

Captured from the Meridian demonstration organization in `evidence/1.7/` (desktop 1440 × 900 for all 14 screens;
tablet 1024 × 768 and mobile 390 × 844 for the executive dashboard, student dashboard, students report, notifications
and integrations). Automated: axe WCAG 2.1 AA (no serious/critical findings) and no horizontal page scroll in
`e2e/insights.spec.ts` on all three viewports.

| Screen | B1 header | B3 cards | B5 tables | B6 filters in URL | B7 states | Charts: single hue, value as text, table view |
|---|---|---|---|---|---|---|
| Executive dashboard | ✓ | ✓ | — | — | ✓ (sections omitted without permission) | — |
| Academic / Staff dashboards | ✓ | ✓ | — | institution selector | ✓ | ✓ bar lists |
| Student dashboard (trends) | ✓ | ✓ | — | institution selector | ✓ | ✓ columns + table view |
| Import operations | ✓ | ✓ | — | date range | ✓ | ✓ |
| Reports / report | ✓ | ✓ | ✓ server paging and sorting | ✓ | ✓ | — |
| Notifications | ✓ | ✓ | list | ✓ state and category | ✓ | — |
| Integrations, API clients, webhooks (list + detail) | ✓ | ✓ | ✓ | status filter (deliveries) | ✓ incl. secret-shown-once panel | — |
