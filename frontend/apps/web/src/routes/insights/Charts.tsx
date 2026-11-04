import { Button, Card, CardHeader, EmptyState, Link, Skeleton } from "@ivorovi/ui";
import { useId, useState } from "react";
import { formatNumber } from "../../lib/format";

export interface GroupRow { id?: string; label?: string; count?: number }
export interface MonthPoint { period?: string; count?: number }

const MONTH = new Intl.DateTimeFormat(undefined, { month: "short" });
const MONTH_YEAR = new Intl.DateTimeFormat(undefined, { month: "long", year: "numeric" });
const monthDate = (p: string) => new Date(`${p}-01T00:00:00`);

/**
 * Ranked single-series distribution (one hue: the theme's primary token, so light and dark mode follow the design
 * system). Thin bars with rounded data ends; the value is text, never color alone; each row can link to its list.
 */
export function BarList({ title, description, rows, loading, href, emptyTitle = "Nothing to show yet", emptyDescription, testId }: {
  title: string; description?: string; rows: GroupRow[] | null | undefined; loading?: boolean; href?: (row: GroupRow) => string | undefined;
  emptyTitle?: string; emptyDescription?: string; testId?: string;
}) {
  const list = rows ?? [];
  const max = Math.max(1, ...list.map((r) => r.count ?? 0));
  const total = list.reduce((s, r) => s + (r.count ?? 0), 0);
  return (
    <Card data-testid={testId}>
      <CardHeader title={title} description={description} />
      {loading ? (
        <div className="space-y-3 p-5 sm:p-6">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-7 w-full" />)}</div>
      ) : list.length === 0 ? (
        <EmptyState title={emptyTitle} description={emptyDescription} />
      ) : (
        <ul className="space-y-3.5 p-5 sm:p-6">
          {list.map((r) => {
            const to = href?.(r);
            const share = total ? Math.round(((r.count ?? 0) / total) * 100) : 0;
            return (
              <li key={r.id ?? r.label} title={`${r.label}: ${formatNumber(r.count)} (${share}%)`}>
                <div className="flex items-baseline justify-between gap-3 text-sm">
                  {to ? <Link to={to} className="min-w-0 truncate font-medium text-fg hover:underline">{r.label}</Link>
                    : <span className="min-w-0 truncate font-medium text-fg">{r.label}</span>}
                  <span className="tabular shrink-0 font-semibold text-fg">{formatNumber(r.count)}<span className="ml-1.5 text-xs font-normal text-muted">{share}%</span></span>
                </div>
                <div aria-hidden className="mt-1.5 h-1.5 rounded-full bg-border">
                  <div className="h-1.5 rounded-full bg-primary" style={{ width: `${Math.max(2, ((r.count ?? 0) / max) * 100)}%` }} />
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

/**
 * Last-12-months column chart (single series, so no legend: the title names it). Columns have a 2px gap and rounded
 * tops anchored to the baseline; hovering or focusing a column shows its value; a table view is always available.
 */
export function MonthlyColumns({ title, description, points, loading, testId }: {
  title: string; description?: string; points: MonthPoint[] | null | undefined; loading?: boolean; testId?: string;
}) {
  const [table, setTable] = useState(false);
  const [hover, setHover] = useState<number | null>(null);
  const id = useId();
  const list = points ?? [];
  const max = Math.max(1, ...list.map((p) => p.count ?? 0));
  const total = list.reduce((s, p) => s + (p.count ?? 0), 0);
  const W = 600, H = 160, gap = 2, base = H - 20;
  const bw = list.length ? (W - gap * (list.length - 1)) / list.length : 0;
  return (
    <Card data-testid={testId}>
      <CardHeader title={title} description={description ?? `${formatNumber(total)} in the last 12 months`}
        actions={<Button variant="ghost" size="sm" aria-pressed={table} aria-controls={id} onClick={() => setTable((t) => !t)}>{table ? "Show chart" : "Show table"}</Button>} />
      <div id={id} className="p-5 sm:p-6">
        {loading ? <Skeleton className="h-40 w-full" /> : list.length === 0 ? <EmptyState title="No data yet" /> : table ? (
          <table className="w-full text-sm">
            <caption className="sr-only">{title}</caption>
            <thead><tr className="text-left text-muted"><th scope="col" className="py-1 font-medium">Month</th><th scope="col" className="py-1 text-right font-medium">Count</th></tr></thead>
            <tbody>
              {list.map((p) => (
                <tr key={p.period} className="border-t border-border">
                  <td className="py-1.5">{MONTH_YEAR.format(monthDate(p.period!))}</td>
                  <td className="tabular py-1.5 text-right">{formatNumber(p.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="relative">
            <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${title}: ${list.map((p) => `${MONTH_YEAR.format(monthDate(p.period!))} ${p.count}`).join(", ")}`}
              className="h-40 w-full overflow-visible" preserveAspectRatio="none">
              <line x1={0} x2={W} y1={base} y2={base} className="stroke-border" strokeWidth={1} vectorEffect="non-scaling-stroke" />
              {list.map((p, i) => {
                const h = ((p.count ?? 0) / max) * (base - 8);
                const x = i * (bw + gap);
                const r = Math.min(4, bw / 2, h);
                const d = h <= 0 ? "" : `M${x},${base} V${base - h + r} Q${x},${base - h} ${x + r},${base - h} H${x + bw - r} Q${x + bw},${base - h} ${x + bw},${base - h + r} V${base} Z`;
                return (
                  <g key={p.period} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
                    {/* hit target taller than the mark */}
                    <rect x={x} y={0} width={bw} height={base} fill="transparent" />
                    {d && <path d={d} className={hover === null || hover === i ? "fill-primary" : "fill-primary opacity-40"} />}
                  </g>
                );
              })}
            </svg>
            <div aria-hidden className="mt-1 flex justify-between text-xs text-muted">
              {list.map((p, i) => <span key={p.period} className={i % 3 === 0 || i === list.length - 1 ? "" : "invisible"}>{MONTH.format(monthDate(p.period!))}</span>)}
            </div>
            {hover !== null && list[hover] && (
              <div role="status" className="pointer-events-none absolute -top-2 rounded-md border border-border bg-surface px-2.5 py-1.5 text-xs shadow-md"
                style={{ left: `${Math.min(80, (hover / list.length) * 100)}%` }}>
                <p className="font-medium text-fg">{MONTH_YEAR.format(monthDate(list[hover].period!))}</p>
                <p className="tabular text-muted">{formatNumber(list[hover].count)}</p>
              </div>
            )}
          </div>
        )}
      </div>
    </Card>
  );
}

/** Section heading row used on every dashboard. */
export function SectionTitle({ children }: { children: string }) {
  return <h2 className="mb-3 mt-8 text-base font-semibold text-fg first:mt-0">{children}</h2>;
}

/** A KPI value: "—" when the viewer may not see the section (never a misleading zero). */
export const kpi = (v: number | null | undefined) => (v === null || v === undefined ? "—" : formatNumber(v));
