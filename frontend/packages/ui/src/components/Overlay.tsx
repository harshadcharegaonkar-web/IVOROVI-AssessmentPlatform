import { Dialog as RadixDialog, DropdownMenu as RadixMenu, Popover as RadixPopover, Tooltip as RadixTooltip } from "radix-ui";
import { X } from "lucide-react";
import type { ReactNode } from "react";
import { cn, focusRing } from "../lib/cn";

/** Account and row-action menus. */
export function Menu({ trigger, children, align = "end" }: { trigger: ReactNode; children: ReactNode; align?: "start" | "end" }) {
  return (
    <RadixMenu.Root>
      <RadixMenu.Trigger asChild>{trigger}</RadixMenu.Trigger>
      <RadixMenu.Portal>
        <RadixMenu.Content align={align} sideOffset={6}
          className="z-50 min-w-56 rounded-xl border border-border bg-surface p-1 text-sm text-fg shadow-lg">
          {children}
        </RadixMenu.Content>
      </RadixMenu.Portal>
    </RadixMenu.Root>
  );
}

export function MenuItem({ children, onSelect, tone }: { children: ReactNode; onSelect: () => void; tone?: "danger" }) {
  return (
    <RadixMenu.Item onSelect={onSelect}
      className={cn("flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 outline-none data-[highlighted]:bg-surface-muted",
        tone === "danger" && "text-danger")}>
      {children}
    </RadixMenu.Item>
  );
}

export function MenuLabel({ children }: { children: ReactNode }) {
  return <RadixMenu.Label className="px-3 py-2">{children}</RadixMenu.Label>;
}

export function MenuSeparator() {
  return <RadixMenu.Separator className="my-1 h-px bg-border" />;
}

export function Popover({ trigger, children, label, open, onOpenChange }: {
  trigger: ReactNode; children: ReactNode; label: string; open?: boolean; onOpenChange?: (o: boolean) => void;
}) {
  return (
    <RadixPopover.Root {...(open !== undefined ? { open } : {})} {...(onOpenChange ? { onOpenChange } : {})}>
      <RadixPopover.Trigger asChild>{trigger}</RadixPopover.Trigger>
      <RadixPopover.Portal>
        <RadixPopover.Content aria-label={label} align="end" sideOffset={8}
          className="z-50 w-[min(24rem,calc(100vw-2rem))] rounded-xl border border-border bg-surface text-sm text-fg shadow-lg">
          {children}
        </RadixPopover.Content>
      </RadixPopover.Portal>
    </RadixPopover.Root>
  );
}

export function TooltipProvider({ children }: { children: ReactNode }) {
  return <RadixTooltip.Provider delayDuration={300}>{children}</RadixTooltip.Provider>;
}

/** Tooltip for icon-only controls (e.g. the collapsed navigation rail). */
export function Tooltip({ content, children, side = "right", disabled }: { content: string; children: ReactNode; side?: "right" | "bottom"; disabled?: boolean }) {
  if (disabled) return <>{children}</>;
  return (
    <RadixTooltip.Root>
      {/* Trigger on a wrapper: Radix's asChild would merge its className into the child's, which breaks
          children with function-valued className (e.g. router NavLink). Focus events bubble from the child. */}
      <RadixTooltip.Trigger asChild><span className="block">{children}</span></RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content side={side} sideOffset={8} className="z-50 rounded-md bg-sidebar px-2 py-1 text-xs font-medium text-white shadow-lg">
          {content}
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  );
}

/** Left side sheet (mobile navigation drawer). Focus is trapped and restored by Radix. */
export function SideSheet({ open, onOpenChange, title, children, className }: {
  open: boolean; onOpenChange: (o: boolean) => void; title: string; children: ReactNode; className?: string;
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/40" />
        <RadixDialog.Content aria-describedby={undefined} className={cn("fixed inset-y-0 left-0 z-50 flex w-72 max-w-[85vw] flex-col shadow-lg", className)}>
          <RadixDialog.Title className="sr-only">{title}</RadixDialog.Title>
          <RadixDialog.Close aria-label="Close navigation" className={cn("absolute right-3 top-3 rounded-md p-2 text-sidebar-text hover:text-white", focusRing)}>
            <X aria-hidden className="h-5 w-5" />
          </RadixDialog.Close>
          {children}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}

/** Centered dialog without the confirm footer (e.g. command/search palette). */
export function Dialog({ open, onOpenChange, title, children, className }: {
  open: boolean; onOpenChange: (o: boolean) => void; title: string; children: ReactNode; className?: string;
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/40" />
        <RadixDialog.Content aria-describedby={undefined}
          className={cn("fixed left-1/2 top-[12vh] z-50 w-[calc(100vw-2rem)] max-w-xl -translate-x-1/2 rounded-xl border border-border bg-surface shadow-lg", className)}>
          <RadixDialog.Title className="sr-only">{title}</RadixDialog.Title>
          {children}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
