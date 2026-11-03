import { unwrap, type StudentSummary } from "@ivorovi/api-client";
import { Button, ButtonLink, Card, DataTable, Field, FilterBar, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Plus, SlidersHorizontal } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { PAGE_SIZE, SearchBox, UnitChoices, useListParams } from "../academics/AcademicsShared";
import { useCourseOptions, useProgramOptions, useYearOptions } from "../academics/useAcademicOptions";
import { STATUSES, statusText } from "./studentModel";
import { StudentAvatar, StudentStatusBadge } from "./StudentShared";

const SORTABLE = ["name", "studentNo", "status", "updatedAt", "batch"];
const FILTERS = ["institutionId", "campusId", "departmentId", "programId", "courseId", "batchId", "academicYearId", "termNumber", "status", "active"];

/** Student search and listing: server-side search, filters, sorting and pagination (state in the URL). */
export function StudentListPage() {
  const api = useApi();
  const labels = useOrgLabels();
  const { access } = useSession();
  const list = useListParams(SORTABLE, "name");
  const f = Object.fromEntries(FILTERS.map((k) => [k, list.filter(k)])) as Record<string, string>;
  const advancedSet = ["institutionId", "campusId", "departmentId", "academicYearId", "termNumber", "active"].some((k) => f[k]);
  const [advanced, setAdvanced] = useState(advancedSet);
  const academic = access.hasAnywhere("academics.read");
  const programs = useProgramOptions(academic);
  const courses = useCourseOptions(f.programId || undefined, academic);
  const years = useYearOptions(academic);
  const batches = useQuery({ queryKey: ["academics", "options", "batches", f.courseId], enabled: academic,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/batches", { params: { query: { size: 100, ...(f.courseId ? { courseId: f.courseId } : {}) } } })).items ?? [] });
  const query = useQuery({
    queryKey: ["students", "list", { q: list.q, ...f, page: list.page, sort: list.sort, dir: list.direction }],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/students", { params: { query: {
      page: list.page, size: PAGE_SIZE, sort: list.sort as "name", direction: list.direction, ...(list.q ? { q: list.q } : {}),
      ...(f.institutionId ? { institutionId: f.institutionId } : {}), ...(f.campusId ? { campusId: f.campusId } : {}),
      ...(f.departmentId ? { departmentId: f.departmentId } : {}), ...(f.programId ? { programId: f.programId } : {}),
      ...(f.courseId ? { courseId: f.courseId } : {}), ...(f.batchId ? { batchId: f.batchId } : {}),
      ...(f.academicYearId ? { academicYearId: f.academicYearId } : {}), ...(f.termNumber ? { termNumber: Number(f.termNumber) } : {}),
      ...(f.status ? { status: f.status as "ACTIVE" } : {}), ...(f.active ? { active: f.active === "true" } : {}) } } })),
  });
  const set = (key: string, value: string, reset: string[] = []) => list.update({ [key]: value, page: null, ...Object.fromEntries(reset.map((r) => [r, null])) });
  const term = labels.singular("TERM");
  const columns: Column<StudentSummary>[] = [
    { key: "name", header: "Student", sortable: true, cell: (s) => (
      <span className="flex min-w-0 items-center gap-3">
        <StudentAvatar name={s.fullName ?? ""} />
        <span className="min-w-0">
          <Link to={`/students/${s.id}`} className="block truncate font-medium text-primary underline-offset-2 hover:underline">{s.fullName}</Link>
          <span className="block truncate text-xs text-muted">{s.studentNo}{s.registrationNo ? ` · Reg. ${s.registrationNo}` : ""}</span>
          <span className="mt-1 flex flex-wrap gap-1 md:hidden"><StudentStatusBadge status={s.status} /></span>
        </span>
      </span>) },
    { key: "studentNo", header: "Student ID", sortable: true, hideOnMobile: true, cell: (s) => <span className="font-mono text-xs">{s.studentNo}</span> },
    { key: "batch", header: `${labels.singular("COURSE")} / ${labels.singular("BATCH").toLowerCase()}`, sortable: true, hideOnMobile: true, cell: (s) => s.courseName ? (
      <span className="block min-w-0"><span className="block truncate">{s.courseName}</span>
        <span className="block truncate text-xs text-muted">{s.batchCode} · {term} {s.termNumber} · {s.academicYearName}</span></span>) : <span className="text-muted">Not enrolled</span> },
    { key: "department", header: "Unit", hideOnMobile: true, cell: (s) => <span className="block min-w-0"><span className="block truncate">{s.departmentName}</span>
      <span className="block truncate text-xs text-muted">{s.institutionName}</span></span> },
    { key: "status", header: "Status", sortable: true, hideOnMobile: true, cell: (s) => <span className="flex flex-wrap gap-1"><StudentStatusBadge status={s.status} />
      {!s.active && <span className="text-xs text-muted">(deactivated)</span>}</span> },
    { key: "updatedAt", header: "Changed", sortable: true, hideOnMobile: true, cell: (s) => formatRelative(s.updatedAt) },
  ];
  const canCreate = access.hasAnywhere("student.manage");
  return (
    <>
      <PageHeader title="Student list" description="Search by name, student ID, registration or roll number, and filter by organization, academic structure and status."
        actions={canCreate ? <ButtonLink to="/students/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New student</ButtonLink> : undefined} />
      <Card>
        <FilterBar trailing={query.isFetching && !query.isPending ? <span className="text-xs text-muted" role="status">Updating…</span> : undefined}>
          <SearchBox label="Search students" placeholder="Name, student ID, registration or roll no." value={list.search} onChange={list.setSearch} />
          {academic && <>
            <Field label={labels.singular("PROGRAM")} className="w-full sm:w-44">
              {(p) => <Select {...p} value={f.programId} onChange={(e) => set("programId", e.target.value, ["courseId", "batchId"])}><option value="">All</option>
                {programs.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</Select>}
            </Field>
            <Field label={labels.singular("COURSE")} className="w-full sm:w-44">
              {(p) => <Select {...p} value={f.courseId} onChange={(e) => set("courseId", e.target.value, ["batchId"])}><option value="">All</option>
                {courses.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</Select>}
            </Field>
            <Field label={labels.singular("BATCH")} className="w-full sm:w-40">
              {(p) => <Select {...p} value={f.batchId} onChange={(e) => set("batchId", e.target.value)}><option value="">All</option>
                {(batches.data ?? []).map((o) => <option key={o.id} value={o.id}>{o.code}</option>)}</Select>}
            </Field>
          </>}
          <Field label="Status" className="w-full sm:w-36">
            {(p) => <Select {...p} value={f.status} onChange={(e) => set("status", e.target.value)}><option value="">All</option>
              {STATUSES.map((s) => <option key={s} value={s}>{statusText(s)}</option>)}</Select>}
          </Field>
          <Button variant="ghost" aria-expanded={advanced} aria-controls="student-more-filters" onClick={() => setAdvanced((a) => !a)}>
            <SlidersHorizontal aria-hidden className="h-4 w-4" /> {advanced ? "Fewer filters" : "More filters"}
          </Button>
        </FilterBar>
        {advanced && (
          <div id="student-more-filters" className="flex flex-wrap items-end gap-3 border-b border-border px-5 pb-4 sm:px-6">
            <Field label={`${labels.singular("UNIVERSITY")} / ${labels.singular("COLLEGE").toLowerCase()}`} className="w-full sm:w-52">
              {(p) => <Select {...p} value={f.institutionId} onChange={(e) => set("institutionId", e.target.value)}><option value="">All</option>
                <UnitChoices permission="student.read" types={["UNIVERSITY", "COLLEGE"]} /></Select>}
            </Field>
            <Field label={labels.singular("CAMPUS")} className="w-full sm:w-44">
              {(p) => <Select {...p} value={f.campusId} onChange={(e) => set("campusId", e.target.value)}><option value="">All</option>
                <UnitChoices permission="student.read" types={["CAMPUS"]} /></Select>}
            </Field>
            <Field label={labels.singular("DEPARTMENT")} className="w-full sm:w-52">
              {(p) => <Select {...p} value={f.departmentId} onChange={(e) => set("departmentId", e.target.value)}><option value="">All</option>
                <UnitChoices permission="student.read" types={["DEPARTMENT"]} /></Select>}
            </Field>
            {academic && <Field label="Academic year" className="w-full sm:w-40">
              {(p) => <Select {...p} value={f.academicYearId} onChange={(e) => set("academicYearId", e.target.value)}><option value="">All</option>
                {years.map((y) => <option key={y.id} value={y.id}>{y.name}</option>)}</Select>}
            </Field>}
            <Field label={term} className="w-full sm:w-28">
              {(p) => <Select {...p} value={f.termNumber} onChange={(e) => set("termNumber", e.target.value)}><option value="">Any</option>
                {Array.from({ length: 12 }, (_, i) => <option key={i + 1} value={i + 1}>{i + 1}</option>)}</Select>}
            </Field>
            <Field label="Profile" className="w-full sm:w-36">
              {(p) => <Select {...p} value={f.active} onChange={(e) => set("active", e.target.value)}><option value="">All</option>
                <option value="true">Active profiles</option><option value="false">Deactivated</option></Select>}
            </Field>
          </div>
        )}
        <DataTable caption="Students" columns={columns} rows={query.data?.items} rowKey={(s) => s.id!}
          loading={query.isPending} error={query.isError ? errorInfo(query.error) : null} onRetry={() => void query.refetch()}
          {...list.tableSort} page={list.pager(query.data)}
          empty={{ title: list.filtered ? "No students match these filters" : "No students yet",
            description: list.filtered ? "Clear a filter or try a different search." : "Create a student profile, record the admission, then enrol the student.",
            action: !list.filtered && canCreate ? <ButtonLink to="/students/new" variant="primary"><Plus aria-hidden className="h-4 w-4" /> New student</ButtonLink> : undefined }} />
      </Card>
    </>
  );
}

export default StudentListPage;
