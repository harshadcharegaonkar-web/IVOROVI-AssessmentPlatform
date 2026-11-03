import { unwrap, type Program } from "@ivorovi/api-client";
import { Badge, ButtonLink, Card, DataTable, Field, FilterBar, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { PAGE_SIZE, SearchBox, StatusBadge, StatusFilter, UnitChoices, useListParams } from "./AcademicsShared";
import { labelOf, QUALIFICATION_LEVELS, TERM_SYSTEMS, type QualificationLevel } from "./academicsModel";

const SORTABLE = ["name", "code", "level", "status", "updatedAt"];

/** Programs (degrees) owned by universities and colleges. */
export function ProgramListPage() {
  const api = useApi();
  const { access } = useSession();
  const labels = useOrgLabels();
  const list = useListParams(SORTABLE, "name");
  const status = list.filter("status");
  const level = list.filter("level");
  const owner = list.filter("owner");
  const query = useQuery({
    queryKey: ["academics", "programs", { q: list.q, status, level, owner, page: list.page, sort: list.sort, dir: list.direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/programs", { params: { query: {
      page: list.page, size: PAGE_SIZE, sort: list.sort, direction: list.direction, ...(list.q ? { q: list.q } : {}),
      ...(status ? { status: status as "ACTIVE" } : {}), ...(level ? { level: level as QualificationLevel } : {}), ...(owner ? { ownerOrgUnitId: owner } : {}) } } })),
  });
  const program = labels.singular("PROGRAM");
  const columns: Column<Program>[] = [
    { key: "name", header: program, sortable: true, cell: (p) => (
      <span className="block min-w-0">
        <Link to={`/academics/programs/${p.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{p.name}</Link>
        <span className="block truncate text-xs text-muted">{p.code}{p.awardedQualification ? ` · ${p.awardedQualification}` : ""}</span>
        <span className="mt-1 flex flex-wrap gap-1 md:hidden"><StatusBadge status={p.status} /><Badge>{labelOf(QUALIFICATION_LEVELS, p.qualificationLevel)}</Badge></span>
      </span>) },
    { key: "level", header: "Level", sortable: true, hideOnMobile: true, cell: (p) => labelOf(QUALIFICATION_LEVELS, p.qualificationLevel) },
    { key: "owner", header: "Owned by", hideOnMobile: true, cell: (p) => p.ownerName },
    { key: "structure", header: "Structure", hideOnMobile: true, cell: (p) => <span className="tabular">{p.durationYears} yr · {p.totalTerms} {labelOf(TERM_SYSTEMS, p.termSystem).split(" ")[0]?.toLowerCase()}</span> },
    { key: "courses", header: labels.plural("COURSE"), cell: (p) => <span className="tabular">{p.courseCount}</span> },
    { key: "status", header: "Status", sortable: true, hideOnMobile: true, cell: (p) => <StatusBadge status={p.status} /> },
    { key: "updatedAt", header: "Changed", sortable: true, hideOnMobile: true, cell: (p) => formatRelative(p.updatedAt) },
  ];
  const canCreate = access.hasAnywhere("program.manage");
  return (
    <>
      <PageHeader title={labels.plural("PROGRAM")} description={`Degrees and qualifications offered by your ${labels.plural("UNIVERSITY").toLowerCase()} and ${labels.plural("COLLEGE").toLowerCase()}. Each ${program.toLowerCase()} contains ${labels.plural("COURSE").toLowerCase()}.`}
        actions={canCreate ? <ButtonLink to="/academics/programs/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {program.toLowerCase()}</ButtonLink> : undefined} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <SearchBox label={`Search ${labels.plural("PROGRAM").toLowerCase()}`} placeholder="Search by name, code or qualification" value={list.search} onChange={list.setSearch} />
          <Field label="Level" className="w-full sm:w-40">
            {(p) => <Select {...p} value={level} onChange={(e) => list.update({ level: e.target.value, page: null })}><option value="">All</option>
              {QUALIFICATION_LEVELS.map((l) => <option key={l.value} value={l.value}>{l.label}</option>)}</Select>}
          </Field>
          <Field label="Owned by" className="w-full sm:w-52">
            {(p) => <Select {...p} value={owner} onChange={(e) => list.update({ owner: e.target.value, page: null })}><option value="">Any</option>
              <UnitChoices permission="academics.read" types={["UNIVERSITY", "COLLEGE"]} /></Select>}
          </Field>
          <StatusFilter value={status} onChange={(v) => list.update({ status: v, page: null })} statuses={["ACTIVE", "INACTIVE"]} />
        </FilterBar>
        <DataTable caption={labels.plural("PROGRAM")} columns={columns} rows={query.data?.items} rowKey={(p) => p.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          {...list.tableSort} page={list.pager(query.data)}
          empty={{
            title: list.filtered ? `No ${labels.plural("PROGRAM").toLowerCase()} match these filters` : `No ${labels.plural("PROGRAM").toLowerCase()} yet`,
            description: list.filtered ? "Clear a filter or try a different search." : `Create the first ${program.toLowerCase()} to start building the academic structure.`,
            action: !list.filtered && canCreate ? <ButtonLink to="/academics/programs/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New {program.toLowerCase()}</ButtonLink> : undefined,
          }} />
      </Card>
    </>
  );
}

export default ProgramListPage;
