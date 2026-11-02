import { unwrap, type InstitutionView } from "@ivorovi/api-client";
import { Badge, Card, DataTable, Input, PageHeader, type Column, type SortState } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../api/ApiProvider";
import { formatDateTime } from "../lib/format";

const PAGE_SIZE = 20;
const SORTABLE = ["name", "code", "createdAt"] as const;

export function InstitutionsPage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const sortKey = (SORTABLE as readonly string[]).includes(params.get("sort") ?? "") ? params.get("sort")! : "name";
  const direction = params.get("dir") === "desc" ? "desc" : "asc";
  const [search, setSearch] = useState(q);

  // Debounce the search box into the URL (shareable, back-button friendly).
  useEffect(() => {
    const t = window.setTimeout(() => {
      if (search !== q) setParams((p) => { const n = new URLSearchParams(p); if (search) n.set("q", search); else n.delete("q"); n.delete("page"); return n; });
    }, 300);
    return () => window.clearTimeout(t);
  }, [search, q, setParams]);

  const query = useQuery({
    queryKey: ["institutions", { q, page, sortKey, direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/institutions", {
      params: { query: { q: q || undefined, page, size: PAGE_SIZE, sort: sortKey, direction } },
    })),
  });

  const update = (changes: Record<string, string | null>) =>
    setParams((p) => { const n = new URLSearchParams(p); Object.entries(changes).forEach(([k, v]) => (v === null ? n.delete(k) : n.set(k, v))); return n; });

  const columns: Column<InstitutionView>[] = [
    { key: "name", header: "Name", sortable: true, cell: (i) => <Link className="font-medium text-primary underline-offset-2 hover:underline" to={`/institutions/${i.id}`}>{i.name}</Link> },
    { key: "code", header: "Code", sortable: true, cell: (i) => <code className="text-xs">{i.code}</code> },
    { key: "status", header: "Status", cell: (i) => <Badge tone={i.status === "ACTIVE" ? "success" : "neutral"}>{i.status}</Badge> },
    { key: "createdAt", header: "Created", sortable: true, hideOnMobile: true, cell: (i) => formatDateTime(i.createdAt) },
  ];

  return (
    <>
      <PageHeader title="Institutions" description="Institutions in your organization that you have access to." />
      <Card>
        <div className="flex flex-wrap items-center gap-3 border-b border-border p-4">
          <label className="relative w-full max-w-sm">
            <span className="sr-only">Search institutions</span>
            <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <Input type="search" placeholder="Search by name or code" value={search} maxLength={100}
              onChange={(e) => setSearch(e.target.value)} className="pl-9" />
          </label>
          {query.isFetching && !query.isPending && <span className="text-xs text-muted" role="status">Updating…</span>}
        </div>
        <DataTable
          caption="Institutions"
          columns={columns}
          rows={query.data?.items}
          rowKey={(i) => i.id!}
          loading={query.isPending}
          error={query.isError ? errorInfo(query.error) : null}
          onRetry={() => void query.refetch()}
          sort={{ key: sortKey, direction } satisfies SortState}
          onSortChange={(s) => update({ sort: s.key, dir: s.direction, page: null })}
          page={query.data ? { page, totalPages: query.data.totalPages ?? 0, totalElements: query.data.totalElements ?? 0, onPageChange: (p) => update({ page: String(p) }) } : undefined}
          empty={{ title: q ? "No institutions match your search" : "No institutions yet", description: q ? "Try a different name or code." : undefined }}
        />
      </Card>
    </>
  );
}
