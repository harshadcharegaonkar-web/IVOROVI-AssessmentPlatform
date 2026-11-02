import { unwrap, type OrgUnitType } from "@ivorovi/api-client";
import { Breadcrumbs, ButtonLink, Card, CardBody, CardHeader, ErrorState, Skeleton } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { OrgStatusBadge } from "./OrgStatusBadge";

/** Compact unit card used beside the hierarchy tree. */
export function OrgUnitSummaryPanel({ id }: { id: string }) {
  const api = useApi();
  const labels = useOrgLabels();
  const q = useQuery({ queryKey: ["org-unit", id], queryFn: async () => unwrap(await api.GET("/api/v1/org-units/{id}", { params: { path: { id } } })) });
  if (q.isPending) return <Card><CardBody className="space-y-3"><Skeleton className="h-6 w-1/2" /><Skeleton className="h-4 w-3/4" /><Skeleton className="h-20 w-full" /></CardBody></Card>;
  if (q.isError) return <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>;
  const { unit, ancestors = [], childCounts = {} } = q.data;
  const type = unit!.type as OrgUnitType;
  const counts = Object.entries(childCounts as Record<string, number>);
  return (
    <Card>
      <CardHeader title={unit!.name} description={`${labels.singular(type)} · ${unit!.code}`}
        actions={<ButtonLink to={`/organization/units/${unit!.id}`} size="sm">Open</ButtonLink>} />
      <CardBody className="space-y-5">
        <div>
          <p className="mb-1 text-xs font-medium uppercase tracking-wider text-muted">Path</p>
          <Breadcrumbs items={[...ancestors.map((a) => ({ label: a.name ?? "", to: `/organization?unit=${a.id}` })), { label: unit!.name ?? "" }]} />
        </div>
        <dl className="grid grid-cols-2 gap-4 text-sm">
          <div><dt className="text-muted">Status</dt><dd className="mt-1"><OrgStatusBadge status={unit!.status} /></dd></div>
          <div><dt className="text-muted">Parent</dt><dd className="mt-1 font-medium">{unit!.parentName ?? "Top level"}</dd></div>
        </dl>
        <div>
          <p className="mb-2 text-xs font-medium uppercase tracking-wider text-muted">Direct sub-units</p>
          {counts.length === 0 ? <p className="text-sm text-muted">None</p> : (
            <ul className="flex flex-wrap gap-2">
              {counts.map(([t, n]) => (
                <li key={t} className="rounded-lg border border-border px-3 py-1.5 text-sm">
                  <span className="tabular font-semibold">{n}</span> {n === 1 ? labels.singular(t as OrgUnitType) : labels.plural(t as OrgUnitType)}
                </li>
              ))}
            </ul>
          )}
        </div>
      </CardBody>
    </Card>
  );
}
