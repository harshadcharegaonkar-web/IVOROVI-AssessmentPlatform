import { unwrap } from "@ivorovi/api-client";
import { Badge, Card, CardBody, CardHeader, EmptyState, ErrorState, Field, Link, PageHeader, Select, Skeleton } from "@ivorovi/ui";
import { useQueries, useQuery } from "@tanstack/react-query";
import { Check, Minus, X } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { LEVEL } from "./accessModel";

const MAX = 4;

/** Side-by-side comparison of up to four roles, grouped by functional area. */
export function RoleComparePage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const ids = (params.get("ids") ?? "").split(",").filter(Boolean).slice(0, MAX);
  const [onlyDiff, setOnlyDiff] = useState(false);
  const roleList = useQuery({ queryKey: ["roles", "list", "all"], queryFn: async () => unwrap(await api.GET("/api/v1/roles", { params: { query: { size: 100 } } })) });
  const catalogue = useQuery({ queryKey: ["permissions"], staleTime: 5 * 60_000, queryFn: async () => unwrap(await api.GET("/api/v1/permissions")) });
  const details = useQueries({ queries: ids.map((id) => ({ queryKey: ["role", id], queryFn: async () => unwrap(await api.GET("/api/v1/roles/{id}", { params: { path: { id } } })) })) });
  const setIds = (next: string[]) => setParams(next.length ? { ids: next.join(",") } : {}, { replace: true });
  const roles = details.map((d) => d.data).filter((d) => !!d);
  const loading = catalogue.isPending || details.some((d) => d.isPending);

  return (
    <>
      <PageHeader title="Compare roles" description="See which permissions each role grants. Differences are highlighted." />
      <Card className="mb-6">
        <CardBody className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {Array.from({ length: MAX }, (_, i) => (
            <Field key={i} label={`Role ${i + 1}`}>
              {(p) => (
                <Select {...p} value={ids[i] ?? ""} onChange={(e) => { const next = [...ids]; if (e.target.value) next[i] = e.target.value; else next.splice(i, 1); setIds(next.filter(Boolean)); }}>
                  <option value="">{i < 2 ? "Choose a role…" : "None"}</option>
                  {(roleList.data?.items ?? []).map((r) => <option key={r.id} value={r.id} disabled={ids.includes(r.id!) && ids[i] !== r.id}>{r.name}{r.system ? " (system)" : ""}</option>)}
                </Select>
              )}
            </Field>
          ))}
          <label className="flex items-center gap-2 text-sm sm:col-span-2 xl:col-span-4">
            <input type="checkbox" className="h-4 w-4 accent-[var(--color-primary)]" checked={onlyDiff} onChange={(e) => setOnlyDiff(e.target.checked)} /> Show only differences
          </label>
        </CardBody>
      </Card>
      {ids.length < 2 ? <Card><EmptyState title="Choose at least two roles" description="Pick roles above to compare their permissions." /></Card>
        : loading ? <Skeleton className="h-96 w-full" />
        : catalogue.isError ? <ErrorState message={errorInfo(catalogue.error).message} /> : (
        <Card>
          <CardHeader title="Permission comparison" description={roles.map((r) => `${r.role?.name}: ${r.permissions?.length ?? 0}`).join(" · ")} />
          <div className="overflow-x-auto">
            <table className="w-full min-w-[36rem] text-left text-sm" data-testid="role-comparison">
              <caption className="sr-only">Permissions by role</caption>
              <thead className="bg-bg text-xs text-muted-strong">
                <tr>
                  <th scope="col" className="px-4 py-3 font-medium uppercase tracking-wider sm:px-6">Permission</th>
                  {roles.map((r) => <th key={r.role?.id} scope="col" className="px-3 py-3 text-center font-semibold normal-case text-fg"><Link to={`/access/roles/${r.role?.id}`} className="hover:underline">{r.role?.name}</Link></th>)}
                </tr>
              </thead>
              {(catalogue.data ?? []).map((d) => {
                const rows = (d.permissions ?? []).filter((p) => p.availability !== "PLANNED").filter((p) => {
                  const has = roles.map((r) => (r.permissions ?? []).includes(p.code!));
                  return has.some(Boolean) && (!onlyDiff || has.some((x) => x !== has[0]));
                });
                if (rows.length === 0) return null;
                return (
                  <tbody key={d.domain} className="divide-y divide-border border-t border-border">
                    <tr><th scope="rowgroup" colSpan={roles.length + 1} className="bg-surface-muted px-4 py-2 text-xs font-semibold uppercase tracking-wider text-muted-strong sm:px-6">{d.label}</th></tr>
                    {rows.map((p) => {
                      const has = roles.map((r) => (r.permissions ?? []).includes(p.code!));
                      const differs = has.some((x) => x !== has[0]);
                      return (
                        <tr key={p.code} className={differs ? "bg-warning-soft/60" : undefined}>
                          <th scope="row" className="px-4 py-2.5 font-normal sm:px-6"><span className="font-medium text-fg">{p.name}</span> <Badge tone={LEVEL[p.level ?? "READ"]!.tone}>{LEVEL[p.level ?? "READ"]!.label}</Badge></th>
                          {has.map((h, i) => (
                            <td key={i} className="px-3 py-2.5 text-center">
                              {h ? <Check aria-label="Included" className="mx-auto h-4 w-4 text-success-text" /> : <X aria-label="Not included" className="mx-auto h-4 w-4 text-muted" />}
                            </td>
                          ))}
                        </tr>
                      );
                    })}
                  </tbody>
                );
              })}
            </table>
          </div>
          {onlyDiff && <p className="flex items-center gap-1 px-6 py-3 text-xs text-muted-strong"><Minus aria-hidden className="h-3 w-3" /> Permissions shared by all selected roles are hidden.</p>}
        </Card>
      )}
    </>
  );
}

export default RoleComparePage;
