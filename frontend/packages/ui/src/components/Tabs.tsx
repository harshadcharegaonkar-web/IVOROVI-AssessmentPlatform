import { Tabs as RadixTabs } from "radix-ui";
import type { ReactNode } from "react";
import { cn, focusRing } from "../lib/cn";

/** Tabs with the design-system underline style (Radix handles roles and arrow-key navigation). */
export function Tabs({ value, onValueChange, tabs, children, label }: {
  value: string;
  onValueChange: (v: string) => void;
  tabs: { value: string; label: string; count?: number | undefined }[];
  children: ReactNode;
  label: string;
}) {
  return (
    <RadixTabs.Root value={value} onValueChange={onValueChange}>
      <RadixTabs.List aria-label={label} className="mb-6 flex gap-1 overflow-x-auto border-b border-border">
        {tabs.map((t) => (
          <RadixTabs.Trigger key={t.value} value={t.value} className={cn(
            "-mb-px flex shrink-0 items-center gap-2 border-b-2 border-transparent px-3 py-2.5 text-sm font-medium text-muted-strong hover:text-fg",
            "data-[state=active]:border-primary data-[state=active]:text-fg", focusRing)}>
            {t.label}
            {t.count !== undefined && <span className="rounded-full bg-surface-muted px-1.5 text-xs text-muted">{t.count}</span>}
          </RadixTabs.Trigger>
        ))}
      </RadixTabs.List>
      {children}
    </RadixTabs.Root>
  );
}

export const TabPanel = RadixTabs.Content;
