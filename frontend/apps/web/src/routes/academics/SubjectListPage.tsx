import { unwrap, type Subject } from "@ivorovi/api-client";
import { Badge, ButtonLink, Card, DataTable, Field, FilterBar, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { PAGE_SIZE, SearchBox, StatusBadge, StatusFilter, UnitChoices, useListParams } from "./AcademicsShared";
import { CATEGORIES, DELIVERY, labelOf, type Category } from "./academicsModel";
import { useCourseOptions } from "./useAcademicOptions";

const SORTABLE = ["name", "code", "credits", "category", "status", "updatedAt"];

/** Subject masters (papers), reusable across courses through curricula. */
export function SubjectListPage() {
  const api = useApi();
  const { access } = useSession();
  const labels = useOrgLabels();
  const list = useListParams(SORTABLE, "code");
  const status = list.filter("status");
  const category = list.filter("category");
  const owner = list.filter("owner");
  const courseId = list.filter("course");
  const courses = useCourseOptions();
  const query = useQuery({
    queryKey: ["academics", "subjects", { q: list.q, status, category, owner, courseId, page: list.page, sort: list.sort, dir: list.direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/subjects", { params: { query: {
      page: list.page, size: PAGE_SIZE, sort: list.sort, direction: list.direction, ...(list.q ? { q: list.q } : {}),
      ...(status ? { status: status as "ACTIVE" } : {}), ...(category ? { category: category as Category } : {}),
      ...(owner ? { ownerOrgUnitId: owner } : {}), ...(courseId ? { courseId } : {}) } } })),
  });
  const noun = labels.singular("SUBJECT");
  const columns: Column<Subject>[] = [
    { key: "code", header: "Code", sortable: true, cell: (s) => <Link to={`/academics/subjects/${s.id}`} className="font-mono font-medium text-primary underline-offset-2 hover:underline">{s.code}</Link> },
    { key: "name", header: noun, sortable: true, cell: (s) => (
      <span className="block min-w-0"><span className="block truncate">{s.name}</span>
        <span className="block truncate text-xs text-muted">{labelOf(DELIVERY, s.delivery)}{s.elective ? " · Elective" : ""}</span></span>) },
    { key: "credits", header: "Credits", sortable: true, cell: (s) => <span className="tabular">{s.credits}</span> },
    { key: "category", header: "Category", sortable: true, hideOnMobile: true, cell: (s) => <Badge>{labelOf(CATEGORIES, s.category)}</Badge> },
    { key: "owner", header: "Owned by", hideOnMobile: true, cell: (s) => s.ownerName },
    { key: "courses", header: `Used in`, hideOnMobile: true, cell: (s) => <span className="tabular">{s.courseCount} {labels.plural("COURSE").toLowerCase()}</span> },
    { key: "status", header: "Status", sortable: true, hideOnMobile: true, cell: (s) => <StatusBadge status={s.status} /> },
    { key: "updatedAt", header: "Changed", sortable: true, hideOnMobile: true, cell: (s) => formatRelative(s.updatedAt) },
  ];
  const canCreate = access.hasAnywhere("subject.manage");
  return (
    <>
      <PageHeader title={labels.plural("SUBJECT")} description={`One master record per ${noun.toLowerCase()}, reused by every ${labels.singular("COURSE").toLowerCase()} and ${labels.singular("TERM").toLowerCase()} that teaches it.`}
        actions={canCreate ? <ButtonLink to="/academics/subjects/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {noun.toLowerCase()}</ButtonLink> : undefined} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <SearchBox label={`Search ${labels.plural("SUBJECT").toLowerCase()}`} placeholder="Search by code, name or description" value={list.search} onChange={list.setSearch} />
          <Field label="Category" className="w-full sm:w-40">
            {(p) => <Select {...p} value={category} onChange={(e) => list.update({ category: e.target.value, page: null })}><option value="">All</option>
              {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}</Select>}
          </Field>
          <Field label={labels.singular("COURSE")} className="w-full sm:w-48">
            {(p) => <Select {...p} value={courseId} onChange={(e) => list.update({ course: e.target.value, page: null })}><option value="">Any</option>
              {courses.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.programCode})</option>)}</Select>}
          </Field>
          <Field label="Owned by" className="w-full sm:w-48">
            {(p) => <Select {...p} value={owner} onChange={(e) => list.update({ owner: e.target.value, page: null })}><option value="">Any</option>
              <UnitChoices permission="academics.read" types={["UNIVERSITY", "COLLEGE", "CAMPUS", "DEPARTMENT"]} /></Select>}
          </Field>
          <StatusFilter value={status} onChange={(v) => list.update({ status: v, page: null })} statuses={["ACTIVE", "INACTIVE"]} />
        </FilterBar>
        <DataTable caption={labels.plural("SUBJECT")} columns={columns} rows={query.data?.items} rowKey={(s) => s.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          {...list.tableSort} page={list.pager(query.data)}
          empty={{
            title: list.filtered ? `No ${labels.plural("SUBJECT").toLowerCase()} match these filters` : `No ${labels.plural("SUBJECT").toLowerCase()} yet`,
            description: list.filtered ? "Clear a filter or try a different search." : undefined,
            action: !list.filtered && canCreate ? <ButtonLink to="/academics/subjects/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {noun.toLowerCase()}</ButtonLink> : undefined,
          }} />
      </Card>
    </>
  );
}

export default SubjectListPage;
