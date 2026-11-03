import { unwrap, type RoleAssignmentOverview } from "@ivorovi/api-client";
import { Badge, Card, DataTable, Field, FilterBar, Input, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { UnitOptions } from "../people/PeopleDialogs";

/** Who holds which role where: current assignments of users the caller can see. */
export function RoleAssignmentsPage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const roleId = params.get("role") ?? "";
  const unit = params.get("unit") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const [search, setSearch] = useState(q);
  useEffect(() => {
    const t = window.setTimeout(() => {
      if (search !== q) setParams((p) => { const n = new URLSearchParams(p); if (search) n.set("q", search); else n.delete("q"); n.delete("page"); return n; });
    }, 300);
    return () => window.clearTimeout(t);
  }, [search, q, setParams]);
  const update = (changes: Record<string, string | null>) =>
    setParams((p) => { const n = new URLSearchParams(p); Object.entries(changes).forEach(([k, v]) => (v ? n.set(k, v) : n.delete(k))); return n; });
  const roles = useQuery({ queryKey: ["roles", "list", "all"], queryFn: async () => unwrap(await api.GET("/api/v1/roles", { params: { query: { size: 100 } } })) });
  const query = useQuery({
    queryKey: ["role-assignments", { q, roleId, unit, page }], placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/role-assignments", { params: { query: { page, size: 20, ...(q ? { q } : {}), ...(roleId ? { roleId } : {}), ...(unit ? { orgUnitId: unit } : {}) } } })),
  });
  const columns: Column<RoleAssignmentOverview>[] = [
    { key: "user", header: "User", cell: (a) => <span className="block min-w-0"><Link to={`/people/users/${a.userId}?tab=roles`} className="block truncate font-medium text-primary hover:underline">{a.userName}</Link><span className="block truncate text-xs text-muted">{a.email}</span></span> },
    { key: "role", header: "Role", cell: (a) => <span className="flex flex-wrap items-center gap-1.5"><Link to={`/access/roles/${a.roleId}`} className="hover:underline">{a.roleName}</Link>
      {!a.systemRole && <Badge>Custom</Badge>}{a.roleStatus === "INACTIVE" && <Badge tone="warning">Role inactive</Badge>}</span> },
    { key: "scope", header: "Scope", cell: (a) => a.orgUnitId ? <Link to={`/organization/units/${a.orgUnitId}`} className="hover:underline">{a.scopeName}</Link> : a.scopeName },
    { key: "valid", header: "Ends", hideOnMobile: true, cell: (a) => a.validTo ? formatDateTime(a.validTo) : <span className="text-muted">Open-ended</span> },
    { key: "by", header: "Granted by", hideOnMobile: true, cell: (a) => <span className="text-xs">{a.grantedByName ?? "System"}<span className="block text-muted">{formatDateTime(a.createdAt)}</span></span> },
  ];
  const filtered = !!(q || roleId || unit);
  return (
    <>
      <PageHeader title="Role assignments" description="Current role assignments of the people you can see. Assign or revoke roles from each user's Roles tab." />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <label className="relative w-full sm:max-w-xs">
            <span className="sr-only">Search assignments</span>
            <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input type="search" placeholder="Search by user or role" value={search} maxLength={100} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
          </label>
          <Field label="Role" className="w-full sm:w-56">
            {(p) => <Select {...p} value={roleId} onChange={(e) => update({ role: e.target.value, page: null })}><option value="">Any</option>
              {(roles.data?.items ?? []).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</Select>}
          </Field>
          <Field label="Within unit" className="w-full sm:w-56">
            {(p) => <Select {...p} value={unit} onChange={(e) => update({ unit: e.target.value, page: null })}><option value="">Anywhere</option><UnitOptions permission="role.read" allowOrganization={false} /></Select>}
          </Field>
        </FilterBar>
        <DataTable caption="Role assignments" columns={columns} rows={query.data?.items} rowKey={(a) => a.assignmentId!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          page={query.data ? { page, totalPages: query.data.totalPages ?? 0, totalElements: query.data.totalElements ?? 0, onPageChange: (p) => update({ page: String(p) }) } : undefined}
          empty={{ title: filtered ? "No assignments match these filters" : "No role assignments", description: filtered ? "Clear a filter or try a different search." : undefined }} />
      </Card>
    </>
  );
}

export default RoleAssignmentsPage;
