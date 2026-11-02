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
| 1 | Dashboard | 1.1 (org KPIs) → 1.6 (full) | ☐ | ☐ | ☐ |
| 2 | Organization hierarchy | 1.1 | ☐ | ☐ | ☐ |
| 3 | Institution listing and details | 1.1 | ☐ | ☐ | ☐ |
| 4 | Campus management | 1.1 | ☐ | ☐ | ☐ |
| 5 | Department management | 1.1 | ☐ | ☐ | ☐ |
| 6 | Academic year management | 1.2 | ☐ | ☐ | ☐ |
| 7 | Program management | 1.5 | ☐ | ☐ | ☐ |
| 8 | Course management | 1.5 | ☐ | ☐ | ☐ |
| 9 | Subject management | 1.5 | ☐ | ☐ | ☐ |
| 10 | Batch management | 1.5 | ☐ | ☐ | ☐ |
| 11 | User management | 1.3 | ☐ | ☐ | ☐ |
| 12 | Faculty management | 1.3 | ☐ | ☐ | ☐ |
| 13 | Student management | 1.6 | ☐ | ☐ | ☐ |
| 14 | Student profile | 1.6 | ☐ | ☐ | ☐ |
| 15 | Bulk student import | 1.6 | ☐ | ☐ | ☐ |
| 16 | Role management | 1.4 | ☐ | ☐ | ☐ |
| 17 | Permission matrix | 1.4 | ☐ | ☐ | ☐ |
| 18 | Access management | 1.4 | ☐ | ☐ | ☐ |
| 19 | Feature settings | 1.0 (restyle) / 1.1 (history, institution inheritance) | ☐ | ☐ | ☐ |
| 20 | Audit logs | 1.0 (restyle) | ☐ | ☐ | ☐ |
| 21 | My Access | 1.0 (restyle) | ☐ | ☐ | ☐ |
| + | System settings | 1.1 | ☐ | ☐ | ☐ |

Automation: Playwright captures the three viewports for every screen into `frontend/apps/web/e2e/__screenshots__`
and runs `toHaveScreenshot` against approved baselines in CI (built into Playwright; no new tool). Baselines are
updated only through a reviewed PR.
