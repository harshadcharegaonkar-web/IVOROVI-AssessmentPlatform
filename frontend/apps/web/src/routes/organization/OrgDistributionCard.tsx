import type { OrgUnitSummary, OrgUnitType } from "@ivorovi/api-client";
import { Card, CardHeader, EmptyState, ErrorState, Link, Skeleton } from "@ivorovi/ui";
import { formatNumber } from "../../lib/format";
import { useOrgLabels } from "../../session/useOrgLabels";

const MAX_ROWS = 6;

/** Dashboard card: campuses and departments per university/college (active units, from /org-units/summary). */
export function OrgDistributionCard({ summary, loading, error, onRetry }: {
  summary: OrgUnitSummary | undefined; loading: boolean; error: string | null; onRetry: () => void;
}) {
  const labels = useOrgLabels();
  const rows = [...(summary?.institutions ?? [])]
    .sort((a, b) => (b.departments ?? 0) - (a.departments ?? 0) || (a.name ?? "").localeCompare(b.name ?? ""));
  const max = Math.max(1, ...rows.map((r) => r.departments ?? 0));
  return (
    <Card data-testid="org-distribution">
      <CardHeader title="Organization distribution"
        description={`${labels.plural("DEPARTMENT")} and ${labels.plural("CAMPUS").toLowerCase()} per institution`}
        actions={<Link to="/organization" className="text-sm font-medium text-primary hover:underline">View hierarchy</Link>} />
      {loading ? (
        <div className="space-y-3 p-5 sm:p-6">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-8 w-full" />)}</div>
      ) : error ? (
        <ErrorState message={error} onRetry={onRetry} />
      ) : rows.length === 0 ? (
        <EmptyState title="No institutions yet" description="Universities and colleges you create appear here." />
      ) : (
        <ul className="space-y-4 p-5 sm:p-6">
          {rows.slice(0, MAX_ROWS).map((r) => (
            <li key={r.id}>
              <div className="flex items-baseline justify-between gap-3 text-sm">
                <Link to={`/organization/units/${r.id}`} className="min-w-0 truncate font-medium text-fg hover:underline">{r.name}</Link>
                <span className="shrink-0 text-xs text-muted">{labels.singular(r.type as OrgUnitType)}</span>
              </div>
              <div aria-hidden className="mt-1.5 h-1.5 rounded-full bg-border">
                <div className="h-1.5 rounded-full bg-primary" style={{ width: `${((r.departments ?? 0) / max) * 100}%` }} />
              </div>
              <p className="mt-1 text-xs text-muted">
                <span className="tabular font-semibold text-fg">{formatNumber(r.departments)}</span> {(r.departments === 1 ? labels.singular("DEPARTMENT") : labels.plural("DEPARTMENT")).toLowerCase()}
                {" · "}
                <span className="tabular font-semibold text-fg">{formatNumber(r.campuses)}</span> {(r.campuses === 1 ? labels.singular("CAMPUS") : labels.plural("CAMPUS")).toLowerCase()}
              </p>
            </li>
          ))}
          {rows.length > MAX_ROWS && <li className="text-xs text-muted">and {rows.length - MAX_ROWS} more</li>}
        </ul>
      )}
    </Card>
  );
}
