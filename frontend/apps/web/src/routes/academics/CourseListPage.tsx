import { unwrap, type Course } from "@ivorovi/api-client";
import { ButtonLink, Card, DataTable, Field, FilterBar, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { PAGE_SIZE, SearchBox, StatusBadge, StatusFilter, UnitChoices, useListParams } from "./AcademicsShared";
import { useProgramOptions } from "./useAcademicOptions";

const SORTABLE = ["name", "code", "program", "department", "status", "updatedAt"];

/** Courses (branches / specializations) of programs, each owned by a department. */
export function CourseListPage() {
  const api = useApi();
  const { access } = useSession();
  const labels = useOrgLabels();
  const list = useListParams(SORTABLE, "name");
  const status = list.filter("status");
  const programId = list.filter("program");
  const departmentId = list.filter("department");
  const programs = useProgramOptions();
  const query = useQuery({
    queryKey: ["academics", "courses", { q: list.q, status, programId, departmentId, page: list.page, sort: list.sort, dir: list.direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/courses", { params: { query: {
      page: list.page, size: PAGE_SIZE, sort: list.sort, direction: list.direction, ...(list.q ? { q: list.q } : {}),
      ...(status ? { status: status as "ACTIVE" } : {}), ...(programId ? { programId } : {}), ...(departmentId ? { departmentId } : {}) } } })),
  });
  const noun = labels.singular("COURSE");
  const columns: Column<Course>[] = [
    { key: "name", header: noun, sortable: true, cell: (c) => (
      <span className="block min-w-0">
        <Link to={`/academics/courses/${c.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{c.name}</Link>
        <span className="block truncate text-xs text-muted">{c.code}{c.specialization ? ` · ${c.specialization}` : ""}</span>
        <span className="mt-1 block truncate text-xs text-muted md:hidden">{c.programName}</span>
      </span>) },
    { key: "program", header: labels.singular("PROGRAM"), sortable: true, hideOnMobile: true, cell: (c) => <Link to={`/academics/programs/${c.programId}`} className="text-primary underline-offset-2 hover:underline">{c.programName}</Link> },
    { key: "department", header: labels.singular("DEPARTMENT"), sortable: true, hideOnMobile: true, cell: (c) => c.departmentName },
    { key: "subjects", header: labels.plural("SUBJECT"), hideOnMobile: true, cell: (c) => <span className="tabular">{c.curriculumCount}</span> },
    { key: "batches", header: labels.plural("BATCH"), cell: (c) => <span className="tabular">{c.batchCount}</span> },
    { key: "status", header: "Status", sortable: true, cell: (c) => <StatusBadge status={c.status} /> },
    { key: "updatedAt", header: "Changed", sortable: true, hideOnMobile: true, cell: (c) => formatRelative(c.updatedAt) },
  ];
  const canCreate = access.hasAnywhere("program.manage");
  return (
    <>
      <PageHeader title={labels.plural("COURSE")} description={`Branches or specializations of a ${labels.singular("PROGRAM").toLowerCase()}, each run by a ${labels.singular("DEPARTMENT").toLowerCase()}.`}
        actions={canCreate ? <ButtonLink to="/academics/courses/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {noun.toLowerCase()}</ButtonLink> : undefined} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <SearchBox label={`Search ${labels.plural("COURSE").toLowerCase()}`} placeholder="Search by name, code or specialization" value={list.search} onChange={list.setSearch} />
          <Field label={labels.singular("PROGRAM")} className="w-full sm:w-52">
            {(p) => <Select {...p} value={programId} onChange={(e) => list.update({ program: e.target.value, page: null })}><option value="">All</option>
              {programs.map((o) => <option key={o.id} value={o.id}>{o.name} ({o.code})</option>)}</Select>}
          </Field>
          <Field label={labels.singular("DEPARTMENT")} className="w-full sm:w-52">
            {(p) => <Select {...p} value={departmentId} onChange={(e) => list.update({ department: e.target.value, page: null })}><option value="">All</option>
              <UnitChoices permission="academics.read" types={["DEPARTMENT"]} /></Select>}
          </Field>
          <StatusFilter value={status} onChange={(v) => list.update({ status: v, page: null })} statuses={["ACTIVE", "INACTIVE"]} />
        </FilterBar>
        <DataTable caption={labels.plural("COURSE")} columns={columns} rows={query.data?.items} rowKey={(c) => c.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          {...list.tableSort} page={list.pager(query.data)}
          empty={{
            title: list.filtered ? `No ${labels.plural("COURSE").toLowerCase()} match these filters` : `No ${labels.plural("COURSE").toLowerCase()} yet`,
            description: list.filtered ? "Clear a filter or try a different search." : `${labels.plural("COURSE")} are added to a ${labels.singular("PROGRAM").toLowerCase()}.`,
            action: !list.filtered && canCreate ? <ButtonLink to="/academics/courses/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {noun.toLowerCase()}</ButtonLink> : undefined,
          }} />
      </Card>
    </>
  );
}

export default CourseListPage;
