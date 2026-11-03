import { unwrap } from "@ivorovi/api-client";
import { Badge, Card, CardHeader, ErrorState, Input, PageHeader, Skeleton } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { LEVEL, SCOPE_RULE } from "./accessModel";

/** The permission catalogue: every permission Ivorovi recognises, by functional area. */
export function PermissionCataloguePage() {
  const api = useApi();
  const [filter, setFilter] = useState("");
  const q = useQuery({ queryKey: ["permissions"], staleTime: 5 * 60_000, queryFn: async () => unwrap(await api.GET("/api/v1/permissions")) });
  const term = filter.trim().toLowerCase();
  return (
    <>
      <PageHeader title="Permission catalogue"
        description="Permissions are defined by Ivorovi and identified by stable codes. Roles combine them; administrators cannot invent new ones. Permissions marked “Coming later” belong to modules that are not available yet." />
      <label className="relative mb-6 block max-w-md">
        <span className="sr-only">Filter permissions</span>
        <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
        <Input type="search" placeholder="Filter by name or code" value={filter} onChange={(e) => setFilter(e.target.value)} className="pl-9" />
      </label>
      {q.isPending ? <Skeleton className="h-96 w-full" /> : q.isError ? <ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /> : (
        <div className="space-y-6">
          {q.data.map((d) => {
            const perms = (d.permissions ?? []).filter((p) => !term || (p.name ?? "").toLowerCase().includes(term) || (p.code ?? "").includes(term));
            if (perms.length === 0) return null;
            return (
              <Card key={d.domain}>
                <CardHeader title={d.label ?? ""} description={`${perms.length} permission(s)`} />
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <caption className="sr-only">{d.label} permissions</caption>
                    <thead className="bg-bg text-xs uppercase tracking-wider text-muted-strong">
                      <tr><th scope="col" className="px-4 py-2.5 font-medium sm:px-6">Permission</th><th scope="col" className="px-4 py-2.5 font-medium">Level</th>
                        <th scope="col" className="hidden px-4 py-2.5 font-medium md:table-cell">Applies to</th><th scope="col" className="px-4 py-2.5 font-medium">Status</th></tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {perms.map((p) => (
                        <tr key={p.code}>
                          <td className="px-4 py-3 sm:px-6"><span className="block font-medium text-fg">{p.name}</span>
                            <span className="block text-muted-strong">{p.description}</span><code className="text-xs text-muted-strong">{p.code}</code></td>
                          <td className="px-4 py-3"><Badge tone={LEVEL[p.level ?? "READ"]!.tone}>{LEVEL[p.level ?? "READ"]!.label}</Badge></td>
                          <td className="hidden px-4 py-3 md:table-cell">{SCOPE_RULE[p.scope ?? "UNIT"]}</td>
                          <td className="px-4 py-3">{p.availability === "PLANNED" ? <Badge>Coming later</Badge> : <Badge tone="success">Available</Badge>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </>
  );
}

export default PermissionCataloguePage;
