# Ivorovi design system: reference analysis and tokens (Phase 1)

* Status: **Draft for approval**. Written before implementation, per the Phase 1 approval (Part 1, steps 1–3).
  Steps 4–6 (shell, dashboard, breakpoint validation) happen in increment 1.0 after Phase 0 CI is green.
* References: **R1**, the "Acadex" university dashboard screenshot (layout and component benchmark), and **R2**,
  "Dashboard layout specification" (Ivorovi colours, KPI card anatomy, navy chrome).
* We reproduce the **design language** (layout, hierarchy, component anatomy). We do not reproduce R1's
  branding, logo, sample people, photos or content. All data shown comes from persisted records.

## 1. Reference analysis

### 1.1 Layout and visual hierarchy

| Element | R1 (measured on the 986 × 733 px screenshot) | R2 | Decision for Ivorovi (CSS px at 1440 × 900) |
|---|---|---|---|
| Navigation chrome | **Light** left sidebar (~213 px ≈ 22% of width), separated by a 1 px divider, no fill contrast | **Navy `#17243A` top bar**, ~66 px high, logo tile + product name + bell + profile | **Navy left sidebar** (written spec) on desktop. Navy **top bar** on tablet and mobile (matches R2, which is the narrow layout). See D-UI-01 |
| Sidebar content | Logo + wordmark → user card (avatar, name, role, chevron) → primary nav (icon + label, ~41 px pitch) → divider → secondary nav (Account, Help, Settings) | — | Logo tile + "IVOROVI / Assessment Platform" → organization context card → grouped nav → divider → My Access, System Settings |
| Page header | Title 22–24 px semibold + date subtitle in muted grey; search/message icons top right | Title "Dashboard" + breadcrumb "Overview / Institution Dashboard" in muted grey | Top bar holds breadcrumbs, institution selector, global search, notifications, account. Page header holds title (24 px/600), description (14 px muted) and actions on the right |
| KPI row | 4 white cards in one row: muted label → large number (≈ 24 px bold) → square light-blue icon tile with blue arrow at right | 2 × 2 grid: blue outline icon top-left → number (≈ 26 px, navy) → label below (muted) | 4 columns ≥ 1280 px, 2 columns ≥ 640 px, 1 column on mobile. Anatomy: icon tile (40 px, blue-50 bg, blue-600 icon) at top-left → value (28 px/700 navy, tabular numerals) → label (14 px muted) → optional delta/secondary line. The whole card links to its list |
| Chart cards | White card, title 16 px semibold left, period select top right, light gridlines, green line with area fill, blue/green grouped bars, small legend dots | — | Same anatomy. Charts use the chart palette (§2.3). Every chart has an accessible data-table alternative |
| Lists | Messages: 32 px avatar, bold name, time right-aligned, two-line muted preview. Activity: 40 px light-blue icon tile, bold title, blue tag line, muted description, relative time | Recent activity: green check icon + one-line text | Activity feed from the audit trail: icon tile (tone by outcome), title, actor · target, relative time |
| Donut | Ring with centred total + legend rows (colour dot, label, % muted, count right) | — | Used for student distribution by status and organisation distribution by type |
| Density | Generous: 20–24 px card padding, 20 px gaps, no heavy shadows | Same | 24 px card padding (20 px on mobile), 24 px grid gap (16 px mobile) |
| Borders/shadows | 1 px very light border (`~#EEF0F4`), near-zero shadow | White cards on `#F4F6FA`, no visible border, soft radius | 1 px `#E2E8F0` border + `shadow-xs` (0 1px 2px rgb(16 24 40 / 0.05)) |
| Radii | ≈ 8–10 px cards, 6–8 px controls | ≈ 12 px cards | Cards 12 px, controls 8 px, badges full |
| Typography | Humanist/geometric sans, bold headings, muted secondary | Inter-like | **Inter** (self-hosted, OFL), tabular numerals for figures |
| Icons | Outline, 1.5–2 px stroke | Outline (Lucide-like) | **Lucide** (already used), 20 px in nav, 16 px inline, stroke 1.75 |

### 1.2 Interaction states observed or implied

* Active nav item: blue icon and label. On a navy sidebar this becomes a white label on a `#2563EB`-tinted pill
  (`rgb(37 99 235 / 0.18)` background, `#FFFFFF` text, 3 px blue left indicator).
* Hover: subtle background shift only. No motion beyond 150 ms colour transitions. No gradients except the chart
  area fill (10% opacity).

## 2. Tokens

### 2.1 Colour (from the product owner's suggestions, adjusted only for WCAG AA)

| Token | Value | Use | Contrast check |
|---|---|---|---|
| `--ivo-sidebar` | `#17243A` | Sidebar, mobile top bar | White text 15.6:1 |
| `--ivo-sidebar-text` | `#CBD5E1` | Inactive nav labels | 10.5:1 on sidebar |
| `--ivo-sidebar-muted` | `#94A3B8` | Group headings, collapsed hints | 6.1:1 |
| `--ivo-primary` | `#2563EB` | Primary buttons, links, active states | White on it 5.2:1; on white 5.2:1 |
| `--ivo-primary-hover` | `#1D4ED8` | | |
| `--ivo-primary-soft` | `#EFF4FF` | Icon tiles, selected rows | |
| `--ivo-bg` | `#F4F6FA` | Workspace background | |
| `--ivo-surface` | `#FFFFFF` | Cards, tables, modals | |
| `--ivo-text` | `#17243A` | Primary text | 15.6:1 / 14.4:1 on bg |
| `--ivo-text-muted` | `#64748B` | Secondary text **on white surfaces** | 4.8:1 on white. **4.4:1 on `#F4F6FA` fails AA** |
| `--ivo-text-muted-strong` | `#475569` | Secondary text placed directly on `--ivo-bg` (page descriptions, breadcrumbs) | 7.0:1 |
| `--ivo-border` | `#E2E8F0` | Card and table borders, dividers (decorative) | |
| `--ivo-border-strong` | `#CBD5E1` | Form control borders (paired with labels) | |
| `--ivo-success` | `#16A34A` | Icons, fills, chart series | 3.3:1 on white: icons/fills only |
| `--ivo-success-text` | `#15803D` | Success text and badges | 5.0:1 |
| `--ivo-warning` | `#D97706` | Icons, fills | 3.2:1: icons/fills only |
| `--ivo-warning-text` | `#B45309` | Warning text and badges | 5.0:1 |
| `--ivo-danger` | `#DC2626` | Errors, destructive buttons, icons, large text | 4.8:1 on white; **4.41:1 on `#FEF2F2` (fails for small text)** |
| `--ivo-danger-text` | `#B91C1C` | Small danger text and badges (added in 1.0 after the axe finding) | 6.5:1 on white, 5.9:1 on `#FEF2F2` |
| `--ivo-focus` | `#2563EB` | 2 px focus ring, 2 px offset | |

Tenant branding (existing `applyBrandColor`) may replace `--ivo-primary` and its contrast colour. Validation keeps
the contrast rule. Navy chrome and neutrals are not brandable, so every tenant gets a consistent product look.

Dark mode: **not in Phase 1**. The Phase 0 dark-mode tokens are removed from the admin app so we don't ship an
unreviewed second theme. This is a documented change and can be reintroduced later as a reviewed theme.

### 2.2 Typography (Inter, 1 rem = 16 px, body 14 px)

| Token | Size / line height / weight | Use |
|---|---|---|
| `display` | 28 / 36 / 700, tabular-nums | KPI values |
| `h1` | 24 / 32 / 600 | Page titles |
| `h2` | 18 / 28 / 600 | Section titles inside pages |
| `h3` | 16 / 24 / 600 | Card titles |
| `body` | 14 / 20 / 400 | Default text, table cells |
| `body-strong` | 14 / 20 / 600 | Names, emphasis |
| `small` | 13 / 18 / 400 | Descriptions, helper text |
| `caption` | 12 / 16 / 500 | Table headers (uppercase, 0.04em tracking), badges, timestamps |

### 2.3 Chart palette

Series 1 `#2563EB`, series 2 `#16A34A`, series 3 `#93C5FD`, series 4 `#0EA5E9`, series 5 `#F59E0B`, neutral
`#CBD5E1`. Validated with the `dataviz` guidance at implementation time. Charts never rely on colour alone:
legends with labels, and values in tooltips and in the table alternative.

### 2.4 Spacing, sizing, elevation

* Spacing scale (4 px base): 4, 8, 12, 16, 20, 24, 32, 40, 48.
* Layout: sidebar **256 px** expanded / **72 px** collapsed (icon rail with tooltips). Top bar **64 px**. Page
  padding 32 px desktop, 24 px tablet, 16 px mobile. Content max-width 1440 px.
* Cards: padding 24 px (20 px < 640 px), radius 12 px, border 1 px `--ivo-border`, `shadow-xs`.
* Controls: height 40 px (36 px compact in tables), radius 8 px. Buttons have 14 px/600 labels and 16 px icons.
* Tables: header row 40 px on `#F8FAFC` with caption-style labels; body rows 52 px; 1 px row dividers; hover `#F8FAFC`; sticky header within the card; numeric columns right-aligned.
* Modals: 480 px (form) / 640 px (complex) width, radius 12 px, 24 px padding, footer actions right-aligned.
* Elevation: `shadow-xs` cards, `shadow-lg` popovers and modals only.

### 2.5 Breakpoints

| Name | Width | Shell behaviour |
|---|---|---|
| Mobile | < 768 px | Navy top bar (56 px) with menu button → full-height navy drawer. Breadcrumbs collapse to a back link. Tables switch to stacked rows for key columns |
| Tablet | 768–1279 px | Navy top bar (64 px) + collapsible navy sidebar (72 px rail by default) |
| Desktop | ≥ 1280 px | Navy sidebar 256 px (user can collapse to 72 px; preference stored per browser) + white top bar 64 px |

Validation viewports: **1440 × 900**, **1024 × 768**, **390 × 844**.

## 3. Application shell

```
┌──────────┬────────────────────────────────────────────────────────────┐
│  NAVY    │ Breadcrumbs        [Institution ▾] [🔍 Search…]  🔔  (AA ▾)│  64 px white top bar
│ SIDEBAR  ├────────────────────────────────────────────────────────────┤
│ 256 px   │  Page title                                 [Secondary][Primary]
│ logo     │  Description (muted)                                        │
│ context  │  ┌ KPI ┐ ┌ KPI ┐ ┌ KPI ┐ ┌ KPI ┐                            │
│ groups…  │  ┌ card ───────────────┐ ┌ card ──────────────┐            │
│          │  …                                                          │
└──────────┴────────────────────────────────────────────────────────────┘
```

Navigation groups (each item is visible only when the user holds the permission; empty groups are hidden):

| Group | Items |
|---|---|
| — | Dashboard |
| Organization | Organization Management (tree) · Institution Management · Campus Management · Department Management |
| Academics | Academic Management (years, programs, courses, subjects, batches) |
| People | User Management · Faculty Management · Student Management |
| Access | Roles & Permissions · Access Management |
| Configuration | Feature Settings · Audit Logs · System Settings |
| (footer) | My Access |

Top bar:
* **Breadcrumbs** derived from route metadata.
* **Institution selector** for users with more than one visible university or college. It sets the context
  filter (URL parameter + per-browser preference). It is **not** a security boundary.
* **Global search** (Ctrl/⌘ K) across org units, users and students the caller may read.
* **Notification bell** with an unread count and a panel of in-app notifications.
* **Account menu**: name, organization, My Access, Sign out.

## 4. Component inventory (all in `packages/ui`, used by every screen)

AppShell, SidebarNav (groups, collapse, active), TopBar, Breadcrumbs, PageHeader, KpiCard, Card/CardHeader,
DataTable (search, filters, sort, pagination, row actions, bulk selection, empty/loading/error), FilterBar,
Tree (keyboard-navigable treeview), Tabs, Button, IconButton, Input, Select, Combobox (async search),
DatePicker (native date input styled), Checkbox, Switch, Field, FormSection, Modal/ConfirmDialog, Drawer,
Badge/StatusBadge, Avatar (initials only; no stock photos), ActivityFeed, NotificationPanel, Toasts,
EmptyState, ErrorState, Skeletons, ProgressBar, FileDropzone, Stepper (import wizard), BarChart,
DonutChart, StackedBar (SVG, no chart library; see D-P1-09).

## 5. Dashboard layout (desktop)

1. **Row 1:** 4 KPI cards: Universities · Colleges · Campuses · Departments.
2. **Row 2:** 4 KPI cards: Students · Faculty · Active users · Pending invitations.
3. **Row 3:** *Student distribution* (donut by status, or bar by program/batch; switchable) | *Organization distribution* (stacked bars: campuses and departments per college).
4. **Row 4:** *Department statistics* table (department, programs, faculty, students, top 10 by students) | *Recent activity* (audit feed).
5. **Row 5:** *Recent user registrations* | *System notifications* | *Quick actions* (permission-filtered buttons: add unit, invite user, register student, import students).

Filters: a context bar under the page header with Institution, Campus, Department and Academic year selects.
Every KPI and chart honours them. All values come from aggregate API endpoints over persisted records, cached
≤ 60 s per tenant + filter.

## 6. Decisions needed

| ID | Question | Recommendation |
|---|---|---|
| D-UI-01 | R1 has a white sidebar. R2 and the written spec use navy chrome | **Navy sidebar on desktop, navy top bar on tablet/mobile** (R2's layout), with R1's component anatomy everywhere else |
| D-UI-02 | Font | **Inter**, self-hosted (OFL-1.1, via the `@fontsource-variable/inter` package). No external font CDN, which keeps the CSP `font-src 'self'` and on-prem offline installs working |
| D-UI-03 | Dark mode | Not in Phase 1 (removed rather than left unreviewed) |
