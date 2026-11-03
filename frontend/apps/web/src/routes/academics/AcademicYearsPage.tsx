import { unwrap, type AcademicYear } from "@ivorovi/api-client";
import { Alert, Button, Card, DataTable, FilterBar, Link, PageHeader, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { PAGE_SIZE, SearchBox, StatusBadge, StatusFilter, useListParams } from "./AcademicsShared";
import { formatRange } from "./academicsModel";
import { YearDialog } from "./CalendarDialogs";

const SORTABLE = ["startDate", "name", "code", "status", "updatedAt"];

/** Academic year management. */
export function AcademicYearsPage() {
  const api = useApi();
  const navigate = useNavigate();
  const { access } = useSession();
  const labels = useOrgLabels();
  const [creating, setCreating] = useState(false);
  const list = useListParams(SORTABLE, "startDate", "desc");
  const status = list.filter("status");
  const query = useQuery({
    queryKey: ["academics", "years", { q: list.q, status, page: list.page, sort: list.sort, dir: list.direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/academic-years", { params: { query: {
      page: list.page, size: PAGE_SIZE, sort: list.sort, direction: list.direction, ...(list.q ? { q: list.q } : {}), ...(status ? { status: status as "ACTIVE" } : {}) } } })),
  });
  const settings = labels.settings as { allowOverlappingAcademicYears?: boolean } | undefined;
  const columns: Column<AcademicYear>[] = [
    { key: "name", header: "Academic year", sortable: true, cell: (y) => (
      <span className="block min-w-0"><Link to={`/academics/calendar/${y.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{y.name}</Link>
        <span className="block truncate text-xs text-muted">{y.code} · {y.ownerName ?? "Whole organization"}</span></span>) },
    { key: "startDate", header: "Dates", sortable: true, cell: (y) => formatRange(y.startDate, y.endDate) },
    { key: "terms", header: labels.plural("TERM"), hideOnMobile: true, cell: (y) => <span className="tabular">{y.termCount}</span> },
    { key: "batches", header: labels.plural("BATCH"), hideOnMobile: true, cell: (y) => <span className="tabular">{y.batchCount}</span> },
    { key: "status", header: "Status", sortable: true, cell: (y) => <StatusBadge status={y.status} /> },
  ];
  const canCreate = access.hasAnywhere("academic_calendar.manage");
  return (
    <>
      <PageHeader title="Academic calendar" description={`Academic years and their ${labels.plural("TERM").toLowerCase()}. Any number of ${labels.plural("TERM").toLowerCase()} per year is supported.`}
        actions={canCreate ? <Button onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New academic year</Button> : undefined} />
      <Alert tone="info" className="mb-4">Overlapping academic years for the same owner are {settings?.allowOverlappingAcademicYears ? "allowed" : "not allowed"} (System Settings).</Alert>
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <SearchBox label="Search academic years" placeholder="Search by name or code" value={list.search} onChange={list.setSearch} />
          <StatusFilter value={status} onChange={(v) => list.update({ status: v, page: null })} statuses={["PLANNED", "ACTIVE", "COMPLETED"]} />
        </FilterBar>
        <DataTable caption="Academic years" columns={columns} rows={query.data?.items} rowKey={(y) => y.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          {...list.tableSort} page={list.pager(query.data)}
          empty={{ title: list.filtered ? "No academic years match these filters" : "No academic years yet",
            action: !list.filtered && canCreate ? <Button onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New academic year</Button> : undefined }} />
      </Card>
      {creating && <YearDialog onClose={() => setCreating(false)} onSaved={(id) => void navigate(`/academics/calendar/${id}`)} />}
    </>
  );
}

export default AcademicYearsPage;
