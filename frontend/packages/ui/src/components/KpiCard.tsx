import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "./link";
import { cn, focusRing } from "../lib/cn";
import { Skeleton } from "./Spinner";

/**
 * KPI card (design system §1.1): icon tile → value → label → optional secondary line. When {@code to} is set,
 * the whole card links to the underlying list.
 */
export function KpiCard({ icon: Icon, label, value, secondary, to, loading, testId }: {
  icon: LucideIcon;
  label: string;
  value: ReactNode;
  secondary?: ReactNode;
  to?: string;
  loading?: boolean;
  testId?: string;
}) {
  const body = (
    <>
      <span aria-hidden className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary-soft text-primary">
        <Icon className="h-5 w-5" strokeWidth={1.75} />
      </span>
      <div className="mt-4">
        {loading ? <Skeleton className="h-8 w-20" /> : (
          <p className="tabular text-[28px] font-bold leading-9 text-fg" data-testid={testId}>{value}</p>
        )}
        <p className="mt-0.5 text-sm text-muted">{label}</p>
        {secondary && <p className="mt-2 text-xs text-muted">{secondary}</p>}
      </div>
    </>
  );
  const cls = "block rounded-xl border border-border bg-surface p-5 shadow-xs sm:p-6";
  return to ? (
    <Link to={to} className={cn(cls, "transition-colors hover:border-border-strong", focusRing)}>{body}</Link>
  ) : (
    <div className={cls}>{body}</div>
  );
}
