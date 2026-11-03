# Frontend performance: initial JavaScript (follow-up to increment 1.4)

Product-owner request (1.4 acceptance): keep the enforced CI budget (initial JavaScript ≤ 180 kB gzip, each lazy chunk
≤ 40 kB), and target 160–165 kB without changing the approved React, React Router, TanStack Query and OIDC architecture
and without compromising authentication, accessibility or functionality.

## Measurement

`pnpm --filter @ivorovi/web budget` reports the entry and its static imports (gzip). Per-source attribution was
obtained from the production source maps of the initial chunks.

| Contributor (initial chunks, before) | ≈ gzip | Decision |
|---|---|---|
| react-dom, react, scheduler | 67 kB | Approved architecture; kept |
| react-router | 30 kB | Approved architecture; kept |
| oidc-client-ts, react-oidc-context | 18 kB | Required for authentication; kept |
| @tanstack/query-core, react-query | 11 kB | Approved architecture; kept |
| Radix overlay primitives (menu, tooltip, popover, dialog) with Floating UI positioning, focus scope, scroll lock | ≈ 20–25 kB | **Moved out of the initial path** (below) |
| Shell, session, UI components, icons, API client | ≈ 15 kB | Kept; icons are already per-icon imports |

The shell used overlays only for the account menu, the notification popover, the mobile navigation drawer and the
collapsed-rail tooltips, yet that forced Radix and Floating UI into the initial JavaScript, and the global
`TooltipProvider` in `main.tsx` pulled Radix Tooltip in even when no tooltip was shown.

## Change

* `@ivorovi/ui/overlay` subpath export; `layout/ShellOverlays.tsx` (the shell's overlay uses) is loaded lazily through
  `layout/LazyShell.tsx`, prefetched with `requestIdleCallback` right after the first render.
* Until it arrives, each trigger renders as the same button with the same accessible name; a click before loading
  requests the module and opens the overlay as soon as it is ready. The mobile drawer mounts on first open; tooltips
  fall back to the plain link (which keeps its `aria-label`).
* `Tooltip` brings its own Radix provider, so the global `TooltipProvider` is no longer needed.

## Result

| | Before | After |
|---|---|---|
| Initial JavaScript (gzip) | 178.0 kB | **154.2 kB** |
| Budget | 180 kB (enforced) | 180 kB (unchanged, enforced) |
| Behaviour | — | 75/75 Playwright tests (desktop, tablet, mobile; axe) and all unit tests pass unchanged |

## Remaining candidates (not needed now)

* The notification list query runs at start-up for the unread badge; a count-only endpoint would cut the first
  request's payload (network, not JavaScript).
* `react-router` data-router features we do not use cannot be tree-shaken further without changing the router API;
  not pursued (approved architecture).
* Consider lowering the enforced budget once two further increments confirm the headroom.
