import { unwrap, type AcademicTerm } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, FilterBar, Link, Menu, MenuItem, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { MoreHorizontal, Pencil } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { PAGE_SIZE, SearchBox, StatusBadge, StatusFilter, useListParams } from "./AcademicsShared";
import { calendarTransitions, formatRange, statusLabel } from "./academicsModel";
import { CalendarStatusDialog, TermDialog } from "./CalendarDialogs";
import { useYearOptions } from "./useAcademicOptions";

const SORTABLE = ["startDate", "name", "sequence", "status", "year"];

/** Semester (term) management across academic years. */
export function TermsPage() {
  const api = useApi();
  const labels = useOrgLabels();
  const years = useYearOptions();
  const [dialog, setDialog] = useState<{ term: AcademicTerm; to?: "PLANNED" | "ACTIVE" | "COMPLETED" } | null>(null);
  const list = useListParams(SORTABLE, "startDate", "desc");
  const status = list.filter("status");
  const yearId = list.filter("year");
  const query = useQuery({
    queryKey: ["academics", "terms", { q: list.q, status, yearId, page: list.page, sort: list.sort, dir: list.direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/academic-terms", { params: { query: {
      page: list.page, size: PAGE_SIZE, sort: list.sort, direction: list.direction, ...(list.q ? { q: list.q } : {}),
      ...(status ? { status: status as "ACTIVE" } : {}), ...(yearId ? { academicYearId: yearId } : {}) } } })),
  });
  const plural = labels.plural("TERM");
  const columns: Column<AcademicTerm>[] = [
    { key: "name", header: labels.singular("TERM"), sortable: true, cell: (t) => <span className="block min-w-0"><span className="block truncate font-medium">{t.name}</span>
      <Link to={`/academics/calendar/${t.academicYearId}`} className="block truncate text-xs text-primary underline-offset-2 hover:underline">{t.academicYearName}</Link></span> },
    { key: "startDate", header: "Dates", sortable: true, cell: (t) => formatRange(t.startDate, t.endDate) },
    { key: "registration", header: "Registration", hideOnMobile: true, cell: (t) => formatRange(t.registrationStart, t.registrationEnd) },
    { key: "exams", header: "Examinations", hideOnMobile: true, cell: (t) => formatRange(t.examStart, t.examEnd) },
    { key: "offerings", header: "Offerings", hideOnMobile: true, cell: (t) => <span className="tabular">{t.offeringCount}</span> },
    { key: "status", header: "Status", sortable: true, cell: (t) => <StatusBadge status={t.status} /> },
    { key: "actions", header: "Actions", cell: (t) => t.canManage ? (
      <Menu trigger={<Button size="sm" variant="ghost" aria-label={`Actions for ${t.name} ${t.academicYearName}`}><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
        {t.status !== "COMPLETED" && <MenuItem onSelect={() => setDialog({ term: t })}><Pencil aria-hidden className="h-4 w-4" /> Edit</MenuItem>}
        {calendarTransitions(t.status).map((tr) => <MenuItem key={tr.to} onSelect={() => setDialog({ term: t, to: tr.to })}>Mark {statusLabel(tr.to).toLowerCase()}{tr.correction ? " (correction)" : ""}</MenuItem>)}
      </Menu>) : null },
  ];
  const year = dialog ? years.find((y) => y.id === dialog.term.academicYearId) : undefined;
  return (
    <>
      <PageHeader title={plural} description={`${plural} of every academic year you can see. Add ${plural.toLowerCase()} from an academic year.`} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <SearchBox label={`Search ${plural.toLowerCase()}`} placeholder="Search by name or year" value={list.search} onChange={list.setSearch} />
          <Field label="Academic year" className="w-full sm:w-48">
            {(p) => <Select {...p} value={yearId} onChange={(e) => list.update({ year: e.target.value, page: null })}><option value="">All</option>
              {years.map((y) => <option key={y.id} value={y.id}>{y.name}{y.ownerName ? ` · ${y.ownerName}` : ""}</option>)}</Select>}
          </Field>
          <StatusFilter value={status} onChange={(v) => list.update({ status: v, page: null })} statuses={["PLANNED", "ACTIVE", "COMPLETED"]} />
        </FilterBar>
        <DataTable caption={plural} columns={columns} rows={query.data?.items} rowKey={(t) => t.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          {...list.tableSort} page={list.pager(query.data)}
          empty={{ title: list.filtered ? `No ${plural.toLowerCase()} match these filters` : `No ${plural.toLowerCase()} yet`,
            description: list.filtered ? "Clear a filter or try a different search." : `Create an academic year, then add its ${plural.toLowerCase()}.` }} />
      </Card>
      {dialog && !dialog.to && year && <TermDialog year={year} term={dialog.term} onClose={() => setDialog(null)} />}
      {dialog?.to && <CalendarStatusDialog kind="term" item={dialog.term} to={dialog.to} onClose={() => setDialog(null)} />}
    </>
  );
}

export default TermsPage;
