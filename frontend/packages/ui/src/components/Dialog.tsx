import { Dialog as RadixDialog } from "radix-ui";
import type { ReactNode } from "react";
import { Button } from "./Button";

/** Confirmation for consequential actions. Focus is trapped and restored by Radix. */
export function ConfirmDialog({ open, onOpenChange, title, description, confirmLabel = "Confirm", tone = "primary", onConfirm, busy = false, children }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: ReactNode;
  confirmLabel?: string;
  tone?: "primary" | "danger";
  onConfirm: () => void;
  busy?: boolean;
  children?: ReactNode;
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-black/40" />
        <RadixDialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-surface p-5 shadow-xl">
          <RadixDialog.Title className="text-base font-semibold text-fg">{title}</RadixDialog.Title>
          <RadixDialog.Description asChild>
            <div className="mt-2 text-sm text-muted">{description}</div>
          </RadixDialog.Description>
          {children}
          <div className="mt-5 flex justify-end gap-2">
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button variant={tone} onClick={onConfirm} loading={busy}>{confirmLabel}</Button>
          </div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
