import { unwrap, type OrgUnit, type OrgUnitType } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, FilterBar, Input, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { NotFoundPage } from "../Guards";
import { formatDateTime } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { useOrgTree } from "../../session/useOrgTree";
import { OrgStatusBadge } from "./OrgStatusBadge";
import { OrgUnitFormDialog } from "./OrgUnitDialogs";
import { ALLOWED_PARENTS, ROUTE_TYPES } from "./orgModel";

const PAGE_SIZE = 20;
const SORTABLE = ["name", "code", "createdAt", "status"];

/** Listing for one unit type (universities, colleges, campuses, departments): search, filters, sort, pages. */
export function OrgUnitListPage() {
  const { typeSlug = "" } = useParams();
  const type = ROUTE_TYPES[typeSlug];
  return type ? <TypeList key={type} type={type} /> : <NotFoundPage />;
}

function TypeList({ type }: { type: OrgUnitType }) {
  const api = useApi();
  const labels = useOrgLabels();
  const { access } = useSession();
  const tree = useOrgTree();
  const [params, setParams] = useSearchParams();
  const [creating, setCreating] = useState(false);
  const q = params.get("q") ?? "";
  const status = params.get("status") ?? "";
  const parentId = params.get("parent") ?? "";
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
    queryKey: ["org-units", "list", { type, q, status, parentId, page, sortKey, direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/org-units", {
      params: { query: { type, page, size: PAGE_SIZE, sort: sortKey as "name", direction,
        ...(q ? { q } : {}), ...(status ? { status: status as "ACTIVE" } : {}), ...(parentId ? { parentId } : {}) } },
    })),
  });

  const parentChoices = (tree.data ?? []).filter((n) => ALLOWED_PARENTS[type].includes(n.type!));
  const columns: Column<OrgUnit>[] = [
    { key: "name", header: "Name", sortable: true, cell: (u) => (
      <span className="block min-w-0">
        <Link to={`/organization/units/${u.id}`} className="font-medium text-primary underline-offset-2 hover:underline">{u.name}</Link>
        {u.shortName && <span className="block text-xs text-muted">{u.shortName}</span>}
      </span>
    ) },
    { key: "code", header: "Code", sortable: true, cell: (u) => <code className="text-xs">{u.code}</code> },
    ...(ALLOWED_PARENTS[type].length ? [{ key: "parent", header: "Parent", hideOnMobile: true,
      cell: (u: OrgUnit) => u.parentId ? <Link to={`/organization/units/${u.parentId}`} className="hover:underline">{u.parentName}</Link> : <span className="text-muted">Top level</span> }] : []),
    { key: "city", header: "City", hideOnMobile: true, cell: (u) => (u.attributes as Record<string, string> | undefined)?.["city"] ?? "—" },
    { key: "status", header: "Status", sortable: true, cell: (u) => <OrgStatusBadge status={u.status} /> },
    { key: "createdAt", header: "Created", sortable: true, hideOnMobile: true, cell: (u) => formatDateTime(u.createdAt) },
  ];

  const canCreate = access.hasAnywhere("org_unit.manage");
  const plural = labels.plural(type);
  return (
    <>
      <PageHeader title={plural} description={`All ${plural.toLowerCase()} you have access to.`}
        actions={canCreate ? <Button onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New {labels.singular(type).toLowerCase()}</Button> : undefined} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <label className="relative w-full sm:max-w-xs">
            <span className="sr-only">Search {plural.toLowerCase()}</span>
            <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input type="search" placeholder="Search by name or code" value={search} maxLength={100} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
          </label>
          <Field label="Status" className="w-full sm:w-40">
            {(p) => (
              <Select {...p} value={status} onChange={(e) => update({ status: e.target.value, page: null })}>
                <option value="">All</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option>
              </Select>
            )}
          </Field>
          {parentChoices.length > 0 && (
            <Field label="Parent" className="w-full sm:w-64">
              {(p) => (
                <Select {...p} value={parentId} onChange={(e) => update({ parent: e.target.value, page: null })}>
                  <option value="">Any</option>
                  {parentChoices.map((n) => <option key={n.id} value={n.id}>{n.name}</option>)}
                </Select>
              )}
            </Field>
          )}
        </FilterBar>
        <DataTable caption={plural} columns={columns} rows={query.data?.items} rowKey={(u) => u.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          sort={{ key: sortKey, direction }} onSortChange={(s) => update({ sort: s.key, dir: s.direction, page: null })}
          page={query.data ? { page, totalPages: query.data.totalPages ?? 0, totalElements: query.data.totalElements ?? 0, onPageChange: (p) => update({ page: String(p) }) } : undefined}
          empty={{
            title: q || status || parentId ? `No ${plural.toLowerCase()} match these filters` : `No ${plural.toLowerCase()} yet`,
            description: q || status || parentId ? "Clear a filter or try a different search." : undefined,
            action: !q && !status && !parentId && canCreate ? <Button onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New {labels.singular(type).toLowerCase()}</Button> : undefined,
          }} />
      </Card>
      {creating && <OrgUnitFormDialog open onOpenChange={setCreating} type={type} parentId={parentId || null} />}
    </>
  );
}

export default OrgUnitListPage;
