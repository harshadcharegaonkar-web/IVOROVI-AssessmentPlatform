import { Menu, MenuItem, MenuLabel, MenuSeparator, Popover, SideSheet, Tooltip } from "@ivorovi/ui/overlay";
import type { ReactNode } from "react";

/**
 * Overlay primitives used by the application shell (account menu, notification popover, mobile navigation drawer,
 * collapsed-rail tooltips). This module pulls in Radix and its positioning engine, so it is loaded after the first
 * render instead of being part of the initial JavaScript (see LazyShell.tsx).
 */
export interface AccountMenuItem { label: string; icon: ReactNode; onSelect: () => void; tone?: "danger" }

export function AccountMenu({ trigger, defaultOpen, header, items }: { trigger: ReactNode; defaultOpen: boolean; header: ReactNode; items: AccountMenuItem[] }) {
  return (
    <Menu trigger={trigger} defaultOpen={defaultOpen}>
      <MenuLabel>{header}</MenuLabel>
      <MenuSeparator />
      {items.map((i) => <MenuItem key={i.label} {...(i.tone ? { tone: i.tone } : {})} onSelect={i.onSelect}>{i.icon} {i.label}</MenuItem>)}
    </Menu>
  );
}

export function NotificationPopover({ trigger, open, onOpenChange, children }: { trigger: ReactNode; open: boolean; onOpenChange: (o: boolean) => void; children: ReactNode }) {
  return <Popover open={open} onOpenChange={onOpenChange} label="Notifications" trigger={trigger}>{children}</Popover>;
}

export function NavigationSheet({ open, onOpenChange, children }: { open: boolean; onOpenChange: (o: boolean) => void; children: ReactNode }) {
  return <SideSheet open={open} onOpenChange={onOpenChange} title="Navigation" className="bg-sidebar">{children}</SideSheet>;
}

export function RailTooltip({ content, children }: { content: string; children: ReactNode }) {
  return <Tooltip content={content}>{children}</Tooltip>;
}
