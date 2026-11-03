import { unwrap, type SubjectOffering } from "@ivorovi/api-client";
import { Card, DataTable, Field, FilterBar, Link, PageHeader, Select, Switch, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { PAGE_SIZE, SearchBox, StatusBadge, StatusFilter, useListParams } from "./AcademicsShared";
import { useCourseOptions, useTermOptions, useYearOptions } from "./useAcademicOptions";

const SORTABLE = ["subject", "batch", "term", "termNumber", "status", "updatedAt"];

/** Subject offering management: what is taught to which batch in which term, and by whom. */
export function OfferingListPage() {
  const api = useApi();
  const labels = useOrgLabels();
  const list = useListParams(SORTABLE, "subject");
  const status = list.filter("status");
  const yearId = list.filter("year");
  const termId = list.filter("term");
  const courseId = list.filter("course");
  const unassigned = list.filter("unassigned") === "1";
  const years = useYearOptions();
  const terms = useTermOptions(yearId || undefined);
  const courses = useCourseOptions();
  const query = useQuery({
    queryKey: ["academics", "offerings", { q: list.q, status, yearId, termId, courseId, unassigned, page: list.page, sort: list.sort, dir: list.direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/subject-offerings", { params: { query: {
      page: list.page, size: PAGE_SIZE, sort: list.sort, direction: list.direction, ...(list.q ? { q: list.q } : {}),
      ...(status ? { status: status as "ACTIVE" } : {}), ...(yearId ? { academicYearId: yearId } : {}), ...(termId ? { termId } : {}),
      ...(courseId ? { courseId } : {}), ...(unassigned ? { withoutFaculty: true } : {}) } } })),
  });
  const term = labels.singular("TERM");
  const columns: Column<SubjectOffering>[] = [
    { key: "subject", header: labels.singular("SUBJECT"), sortable: true, cell: (o) => (
      <span className="block min-w-0"><Link to={`/academics/offerings/${o.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{o.subjectCode} · {o.subjectName}</Link>
        <span className="block truncate text-xs text-muted md:hidden">{o.batchCode} · {o.termName}</span></span>) },
    { key: "batch", header: labels.singular("BATCH"), sortable: true, hideOnMobile: true, cell: (o) => <span className="block min-w-0"><Link to={`/academics/batches/${o.batchId}`} className="block truncate text-primary underline-offset-2 hover:underline">{o.batchCode}</Link>
      <span className="block text-xs text-muted">{o.courseCode}{o.section ? ` · Section ${o.section}` : ""}</span></span> },
    { key: "term", header: term, sortable: true, hideOnMobile: true, cell: (o) => <span className="block min-w-0"><span className="block truncate">{o.termName}</span><span className="block text-xs text-muted">{o.academicYearName}</span></span> },
    { key: "termNumber", header: `${labels.singular("COURSE")} ${term.toLowerCase()}`, sortable: true, hideOnMobile: true, cell: (o) => <span className="tabular">{o.termNumber}</span> },
    { key: "faculty", header: "Faculty", cell: (o) => o.primaryFaculty ? <span>{o.primaryFaculty}{(o.facultyCount ?? 0) > 1 ? <span className="text-muted"> +{(o.facultyCount ?? 0) - 1}</span> : null}</span> : <span className="text-warning-strong">Not assigned</span> },
    { key: "status", header: "Status", sortable: true, cell: (o) => <StatusBadge status={o.status} /> },
  ];
  return (
    <>
      <PageHeader title="Subject offerings" description={`${labels.plural("SUBJECT")} offered to a ${labels.singular("BATCH").toLowerCase()} in a calendar ${term.toLowerCase()}. Offer a subject from its ${labels.singular("BATCH").toLowerCase()}'s page.`} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <SearchBox label="Search offerings" placeholder="Search by subject or batch" value={list.search} onChange={list.setSearch} />
          <Field label="Academic year" className="w-full sm:w-40">
            {(p) => <Select {...p} value={yearId} onChange={(e) => list.update({ year: e.target.value, term: null, page: null })}><option value="">All</option>
              {years.map((y) => <option key={y.id} value={y.id}>{y.name}</option>)}</Select>}
          </Field>
          <Field label={term} className="w-full sm:w-44">
            {(p) => <Select {...p} value={termId} onChange={(e) => list.update({ term: e.target.value, page: null })}><option value="">All</option>
              {terms.map((t) => <option key={t.id} value={t.id}>{t.name} · {t.academicYearName}</option>)}</Select>}
          </Field>
          <Field label={labels.singular("COURSE")} className="w-full sm:w-44">
            {(p) => <Select {...p} value={courseId} onChange={(e) => list.update({ course: e.target.value, page: null })}><option value="">All</option>
              {courses.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select>}
          </Field>
          <StatusFilter value={status} onChange={(v) => list.update({ status: v, page: null })} statuses={["PLANNED", "ACTIVE", "COMPLETED", "CANCELLED"]} />
          <div className="flex items-center gap-2 pb-2 text-sm">
            <Switch id="unassigned" checked={unassigned} label="Only offerings without faculty" onCheckedChange={(c) => list.update({ unassigned: c ? "1" : null, page: null })} />
            <label htmlFor="unassigned" className="text-fg">Without faculty</label>
          </div>
        </FilterBar>
        <DataTable caption="Subject offerings" columns={columns} rows={query.data?.items} rowKey={(o) => o.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          {...list.tableSort} page={list.pager(query.data)}
          empty={{ title: list.filtered ? "No offerings match these filters" : "No subject offerings yet",
            description: list.filtered ? "Clear a filter or try a different search." : `Open a ${labels.singular("BATCH").toLowerCase()} and offer ${labels.plural("SUBJECT").toLowerCase()} from its curriculum.` }} />
      </Card>
    </>
  );
}

export default OfferingListPage;
