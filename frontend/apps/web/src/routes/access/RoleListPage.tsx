import { unwrap, type RoleSummary } from "@ivorovi/api-client";
import { Badge, ButtonLink, Card, DataTable, Field, FilterBar, Input, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Columns3, Plus, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";

const PAGE_SIZE = 20;
const SORTABLE = ["name", "code", "status", "updatedAt", "permissions"];

/** System and custom roles: what each role is for, where it applies and how many people hold it. */
export function RoleListPage() {
  const api = useApi();
  const { access } = useSession();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const type = params.get("type") ?? "";
  const status = params.get("status") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const sortKey = SORTABLE.includes(params.get("sort") ?? "") ? params.get("sort")! : "name";
  const direction = params.get("dir") === "desc" ? "desc" : "asc";
  const [search, setSearch] = useState(q);

  useEffect(() => {
    const t = window.setTimeout(() => {
      if (search !== q) setParams((p) => { const n = new URLSearchParams(p); if (search) n.set("q", search); else n.delete("q"); n.delete("page"); return n; });
    }, 300);
    return () => window.clearTimeout(t);
  }, [search, q, setParams]);
  const update = (changes: Record<string, string | null>) =>
    setParams((p) => { const n = new URLSearchParams(p); Object.entries(changes).forEach(([k, v]) => (v ? n.set(k, v) : n.delete(k))); return n; });

  const query = useQuery({
    queryKey: ["roles", "list", { q, type, status, page, sortKey, direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/roles", { params: { query: { page, size: PAGE_SIZE, sort: sortKey as "name", direction,
      ...(q ? { q } : {}), ...(type ? { type: type as "system" } : {}), ...(status ? { status: status as "ACTIVE" } : {}) } } })),
  });

  const columns: Column<RoleSummary>[] = [
    { key: "name", header: "Role", sortable: true, cell: (r) => (
      <span className="block min-w-0">
        <Link to={`/access/roles/${r.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{r.name}</Link>
        <span className="block truncate text-xs text-muted">{r.description || r.code}</span>
        <span className="mt-1 flex flex-wrap gap-1 md:hidden">
          <Badge tone={r.system ? "info" : "neutral"}>{r.system ? "System" : "Custom"}</Badge>
          {r.status === "INACTIVE" && <Badge tone="warning">Inactive</Badge>}
        </span>
      </span>
    ) },
    { key: "type", header: "Type", hideOnMobile: true, cell: (r) => <Badge tone={r.system ? "info" : "neutral"}>{r.system ? "System" : "Custom"}</Badge> },
    { key: "scope", header: "Usable in", hideOnMobile: true, cell: (r) => r.scopeName },
    { key: "permissions", header: "Permissions", sortable: true, hideOnMobile: true, cell: (r) => <span className="tabular">{r.permissionCount}</span> },
    { key: "holders", header: "Holders", cell: (r) => <span className="tabular">{r.activeAssignments}</span> },
    { key: "status", header: "Status", sortable: true, hideOnMobile: true, cell: (r) => r.status === "ACTIVE" ? <Badge tone="success">Active</Badge> : <Badge tone="warning">Inactive</Badge> },
    { key: "updatedAt", header: "Changed", sortable: true, hideOnMobile: true, cell: (r) => r.system ? <span className="text-muted">—</span> : formatRelative(r.updatedAt) },
  ];
  const filtered = !!(q || type || status);

  return (
    <>
      <PageHeader title="Roles" description="A role is a named set of permissions. System roles are defined by Ivorovi; custom roles are defined by your organization."
        actions={<>
          <ButtonLink to="/access/roles/compare"><Columns3 aria-hidden className="h-4 w-4" /> Compare roles</ButtonLink>
          {access.hasAnywhere("role.manage") && <ButtonLink to="/access/roles/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New role</ButtonLink>}
        </>} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <label className="relative w-full sm:max-w-xs">
            <span className="sr-only">Search roles</span>
            <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input type="search" placeholder="Search by name, code or description" value={search} maxLength={100} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
          </label>
          <Field label="Type" className="w-full sm:w-36">
            {(p) => <Select {...p} value={type} onChange={(e) => update({ type: e.target.value, page: null })}><option value="">All</option><option value="system">System</option><option value="custom">Custom</option></Select>}
          </Field>
          <Field label="Status" className="w-full sm:w-36">
            {(p) => <Select {...p} value={status} onChange={(e) => update({ status: e.target.value, page: null })}><option value="">All</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option></Select>}
          </Field>
        </FilterBar>
        <DataTable caption="Roles" columns={columns} rows={query.data?.items} rowKey={(r) => r.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          sort={{ key: sortKey, direction }} onSortChange={(s) => update({ sort: s.key, dir: s.direction, page: null })}
          page={query.data ? { page, totalPages: query.data.totalPages ?? 0, totalElements: query.data.totalElements ?? 0, onPageChange: (p) => update({ page: String(p) }) } : undefined}
          empty={{
            title: filtered ? "No roles match these filters" : "No roles",
            description: filtered ? "Clear a filter or try a different search." : undefined,
            action: !filtered && access.hasAnywhere("role.manage") ? <ButtonLink to="/access/roles/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New role</ButtonLink> : undefined,
          }} />
      </Card>
    </>
  );
}

export default RoleListPage;
