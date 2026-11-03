import { unwrap, type Batch } from "@ivorovi/api-client";
import { ButtonLink, Card, DataTable, Field, FilterBar, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { PAGE_SIZE, SearchBox, StatusBadge, StatusFilter, useListParams } from "./AcademicsShared";
import { useCourseOptions, useYearOptions } from "./useAcademicOptions";

const SORTABLE = ["name", "code", "admissionYear", "course", "status", "updatedAt"];

/** Batches (intakes) of courses. */
export function BatchListPage() {
  const api = useApi();
  const { access } = useSession();
  const labels = useOrgLabels();
  const list = useListParams(SORTABLE, "admissionYear", "desc");
  const status = list.filter("status");
  const courseId = list.filter("course");
  const yearId = list.filter("year");
  const admission = list.filter("admission");
  const courses = useCourseOptions();
  const years = useYearOptions();
  const query = useQuery({
    queryKey: ["academics", "batches", { q: list.q, status, courseId, yearId, admission, page: list.page, sort: list.sort, dir: list.direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/batches", { params: { query: {
      page: list.page, size: PAGE_SIZE, sort: list.sort, direction: list.direction, ...(list.q ? { q: list.q } : {}),
      ...(status ? { status: status as "ACTIVE" } : {}), ...(courseId ? { courseId } : {}), ...(yearId ? { academicYearId: yearId } : {}),
      ...(admission ? { admissionYear: Number(admission) } : {}) } } })),
  });
  const noun = labels.singular("BATCH");
  const columns: Column<Batch>[] = [
    { key: "name", header: noun, sortable: true, cell: (b) => (
      <span className="block min-w-0"><Link to={`/academics/batches/${b.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{b.name}</Link>
        <span className="block truncate text-xs text-muted">{b.code}{b.section ? ` · Section ${b.section}` : ""}</span>
        <span className="mt-1 block truncate text-xs text-muted md:hidden">{b.courseName}</span></span>) },
    { key: "course", header: labels.singular("COURSE"), sortable: true, hideOnMobile: true, cell: (b) => <span className="block min-w-0"><Link to={`/academics/courses/${b.courseId}`} className="block truncate text-primary underline-offset-2 hover:underline">{b.courseName}</Link>
      <span className="block truncate text-xs text-muted">{b.programName}</span></span> },
    { key: "admissionYear", header: "Admitted", sortable: true, cell: (b) => <span className="tabular">{b.admissionYear}</span> },
    { key: "grad", header: "Graduates", hideOnMobile: true, cell: (b) => <span className="tabular">{b.expectedGraduationYear}</span> },
    { key: "capacity", header: "Capacity", hideOnMobile: true, cell: (b) => <span className="tabular">{b.capacity ?? "—"}</span> },
    { key: "offerings", header: "Offerings", hideOnMobile: true, cell: (b) => <span className="tabular">{b.offeringCount}</span> },
    { key: "status", header: "Status", sortable: true, hideOnMobile: true, cell: (b) => <StatusBadge status={b.status} /> },
  ];
  const canCreate = access.hasAnywhere("batch.manage");
  const thisYear = new Date().getFullYear();
  return (
    <>
      <PageHeader title={labels.plural("BATCH")} description={`Intakes of a ${labels.singular("COURSE").toLowerCase()}; a ${labels.singular("COURSE").toLowerCase()} can have several ${labels.plural("BATCH").toLowerCase()} and sections per year.`}
        actions={canCreate ? <ButtonLink to="/academics/batches/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {noun.toLowerCase()}</ButtonLink> : undefined} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <SearchBox label={`Search ${labels.plural("BATCH").toLowerCase()}`} placeholder="Search by name, code or section" value={list.search} onChange={list.setSearch} />
          <Field label={labels.singular("COURSE")} className="w-full sm:w-48">
            {(p) => <Select {...p} value={courseId} onChange={(e) => list.update({ course: e.target.value, page: null })}><option value="">All</option>
              {courses.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.programCode})</option>)}</Select>}
          </Field>
          <Field label="Academic year" className="w-full sm:w-40">
            {(p) => <Select {...p} value={yearId} onChange={(e) => list.update({ year: e.target.value, page: null })}><option value="">All</option>
              {years.map((y) => <option key={y.id} value={y.id}>{y.name}</option>)}</Select>}
          </Field>
          <Field label="Admitted" className="w-full sm:w-28">
            {(p) => <Select {...p} value={admission} onChange={(e) => list.update({ admission: e.target.value, page: null })}><option value="">Any</option>
              {Array.from({ length: 12 }, (_, i) => thisYear + 1 - i).map((y) => <option key={y} value={y}>{y}</option>)}</Select>}
          </Field>
          <StatusFilter value={status} onChange={(v) => list.update({ status: v, page: null })} statuses={["PLANNED", "ACTIVE", "GRADUATED", "INACTIVE"]} />
        </FilterBar>
        <DataTable caption={labels.plural("BATCH")} columns={columns} rows={query.data?.items} rowKey={(b) => b.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          {...list.tableSort} page={list.pager(query.data)}
          empty={{ title: list.filtered ? `No ${labels.plural("BATCH").toLowerCase()} match these filters` : `No ${labels.plural("BATCH").toLowerCase()} yet`,
            description: list.filtered ? "Clear a filter or try a different search." : undefined,
            action: !list.filtered && canCreate ? <ButtonLink to="/academics/batches/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {noun.toLowerCase()}</ButtonLink> : undefined }} />
      </Card>
    </>
  );
}

export default BatchListPage;
