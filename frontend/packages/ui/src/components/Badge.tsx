import type { ReactNode } from "react";
import { cn } from "../lib/cn";

export type BadgeTone = "neutral" | "success" | "danger" | "warning" | "info";

const tones: Record<BadgeTone, string> = {
  neutral: "bg-surface-muted text-muted",
  success: "bg-success-soft text-success",
  danger: "bg-danger-soft text-danger",
  warning: "bg-warning-soft text-warning",
  info: "bg-info-soft text-primary",
};

export function Badge({ tone = "neutral", children, className }: { tone?: BadgeTone; children: ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap", tones[tone], className)}>
      {children}
    </span>
  );
}
