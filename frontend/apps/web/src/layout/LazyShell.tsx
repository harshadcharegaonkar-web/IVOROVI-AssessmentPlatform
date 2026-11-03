import { lazy, Suspense, useEffect, useState, type ReactNode } from "react";
import type { AccountMenuItem } from "./ShellOverlays";

/**
 * Lazy shell overlays (performance task, follow-up to increment 1.4). Each overlay renders its normal trigger
 * immediately; the overlay module is fetched when the browser is idle after the first render, or on first use.
 * Behaviour, keyboard handling and accessible names are unchanged once loaded; before that, triggers are plain
 * buttons, and a click requests the module and opens the overlay as soon as it arrives.
 */
const load = () => import("./ShellOverlays");
const AccountMenuImpl = lazy(() => load().then((m) => ({ default: m.AccountMenu })));
const NotificationPopoverImpl = lazy(() => load().then((m) => ({ default: m.NotificationPopover })));
const NavigationSheetImpl = lazy(() => load().then((m) => ({ default: m.NavigationSheet })));
const RailTooltipImpl = lazy(() => load().then((m) => ({ default: m.RailTooltip })));

/** Starts loading the overlay module once the page is idle (or after a short delay where idle callbacks are missing). */
export function usePrefetchShellOverlays() {
  useEffect(() => {
    const w = window as Window & { requestIdleCallback?: (cb: () => void, o?: { timeout: number }) => number; cancelIdleCallback?: (id: number) => void };
    if (w.requestIdleCallback) {
      const id = w.requestIdleCallback(() => void load(), { timeout: 2000 });
      return () => w.cancelIdleCallback?.(id);
    }
    const t = window.setTimeout(() => void load(), 1200);
    return () => window.clearTimeout(t);
  }, []);
}

/** Account menu; {@code trigger} is rendered as-is while the menu module loads. */
export function AccountMenu({ trigger, header, items }: {
  trigger: (props: { onClick?: () => void }) => ReactNode; header: ReactNode; items: AccountMenuItem[];
}) {
  const [requested, setRequested] = useState(false);
  return (
    <Suspense fallback={trigger({ onClick: () => { setRequested(true); void load(); } })}>
      <AccountMenuImpl trigger={trigger({})} defaultOpen={requested} header={header} items={items} />
    </Suspense>
  );
}

export function NotificationPopover({ trigger, open, onOpenChange, children }: { trigger: (props: { onClick?: () => void }) => ReactNode; open: boolean; onOpenChange: (o: boolean) => void; children: ReactNode }) {
  return (
    <Suspense fallback={trigger({ onClick: () => { onOpenChange(true); void load(); } })}>
      <NotificationPopoverImpl trigger={trigger({})} open={open} onOpenChange={onOpenChange}>{children}</NotificationPopoverImpl>
    </Suspense>
  );
}

/** Mobile navigation drawer: only mounted once it has been opened. */
export function NavigationSheet({ open, onOpenChange, children }: { open: boolean; onOpenChange: (o: boolean) => void; children: ReactNode }) {
  const [used, setUsed] = useState(open);
  if (open && !used) setUsed(true);
  if (!used) return null;
  return <Suspense fallback={null}><NavigationSheetImpl open={open} onOpenChange={onOpenChange}>{children}</NavigationSheetImpl></Suspense>;
}

/** Tooltip for the collapsed navigation rail (the link keeps its aria-label either way). */
export function RailTooltip({ content, disabled, children }: { content: string; disabled: boolean; children: ReactNode }) {
  if (disabled) return <>{children}</>;
  return <Suspense fallback={children}><RailTooltipImpl content={content}>{children}</RailTooltipImpl></Suspense>;
}
