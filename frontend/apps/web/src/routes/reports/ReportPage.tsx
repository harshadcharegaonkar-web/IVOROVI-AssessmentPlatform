import { unwrap, type ReportResult } from "@ivorovi/api-client";
import { Alert, Button, Card, DataTable, EmptyState, ErrorState, Field, FilterBar, Input, PageHeader, Select, Switch, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { useMemo } from "react";
import { useParams, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { humanize } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { UnitOptions } from "../people/PeopleDialogs";
import { useDownload } from "../students/imports/ImportShared";

type Row = { i: number; cells: string[] };
const FILTER_KEYS = ["from", "to", "orgUnitId", "academicYearId", "programId", "courseId", "batchId", "status", "includePersonal", "sort", "direction"] as const;

/** One report: filters in the URL, server-side paging and sorting, CSV/XLSX export of every matching row (audited). */
export function ReportPage() {
  const { key = "" } = useParams();
  const api = useApi();
  const labels = useOrgLabels();
  const { access } = useSession();
  const download = useDownload();
  const [params, setParams] = useSearchParams();
  const page = Number(params.get("page") ?? 0);
  const f = Object.fromEntries(FILTER_KEYS.map((k) => [k, params.get(k) ?? ""])) as Record<(typeof FILTER_KEYS)[number], string>;
  const update = (patch: Record<string, string | null>) => setParams((p) => {
    const n = new URLSearchParams(p);
    for (const [k, v] of Object.entries(patch)) { if (v) n.set(k, v); else n.delete(k); }
    if (!("page" in patch)) n.delete("page");
    return n;
  }, { replace: true });

  const catalogue = useQuery({ queryKey: ["reports", "catalogue"], queryFn: async () => unwrap(await api.GET("/api/v1/reports")) });
  const def = catalogue.data?.find((d) => d.key === key);
  const has = (filter: string) => def?.filters?.includes(filter) ?? false;
  const search = params.toString();
  const filters = useMemo(() => {
    const q: Record<string, string | boolean> = {};
    const sp = new URLSearchParams(search);
    for (const k of FILTER_KEYS) { const v = sp.get(k); if (v) q[k] = k === "includePersonal" ? v === "true" : v; }
    return q;
  }, [search]);
  const query = useMemo(() => ({ ...filters, page, size: 25 }), [filters, page]);
  const result = useQuery({
    queryKey: ["reports", key, query], enabled: !!def, placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/reports/{key}", { params: { path: { key }, query: query as never } })),
  });
  const years = useQuery({ queryKey: ["report-filter", "years"], enabled: has("academicYearId"), queryFn: async () => unwrap(await api.GET("/api/v1/academics/academic-years", { params: { query: { size: 100 } } })) });
  const programs = useQuery({ queryKey: ["report-filter", "programs"], enabled: has("programId"), queryFn: async () => unwrap(await api.GET("/api/v1/academics/programs", { params: { query: { size: 100 } } })) });
  const courses = useQuery({ queryKey: ["report-filter", "courses", f.programId], enabled: has("courseId"), queryFn: async () =>
    unwrap(await api.GET("/api/v1/academics/courses", { params: { query: { size: 100, ...(f.programId ? { programId: f.programId } : {}) } } })) });
  const batches = useQuery({ queryKey: ["report-filter", "batches", f.courseId, f.academicYearId], enabled: has("batchId"), queryFn: async () =>
    unwrap(await api.GET("/api/v1/academics/batches", { params: { query: { size: 100, ...(f.courseId ? { courseId: f.courseId } : {}), ...(f.academicYearId ? { academicYearId: f.academicYearId } : {}) } } })) });

  const data: ReportResult | undefined = result.data;
  const columns: Column<Row>[] = (data?.columns ?? def?.columns?.filter((c) => !c.personal) ?? []).map((c, idx) => ({
    key: c.key!, header: c.label!, sortable: def?.sorts?.includes(c.key!) ?? false, cell: (r: Row) => r.cells[idx] || "—",
  }));
  const rows: Row[] | undefined = data?.rows?.map((cells, i) => ({ i, cells }));
  const exportFile = (format: "CSV" | "XLSX") => {
    void download(() => api.GET("/api/v1/reports/{key}/export", { params: { path: { key }, query: { ...filters, format } as never }, parseAs: "blob" }),
      `ivorovi-${key}.${format === "CSV" ? "csv" : "xlsx"}`);
  };
  const personalAllowed = !!def?.personalAvailable && access.hasAnywhere("student.personal.read");

  if (catalogue.isError) return <Card><ErrorState message={errorInfo(catalogue.error).message} onRetry={() => void catalogue.refetch()} /></Card>;
  if (catalogue.isSuccess && !def) return <Card><EmptyState title="Report not available" description="This report does not exist or your roles do not include its records." /></Card>;
  return (
    <>
      <PageHeader title={def?.title ?? "Report"} description={def?.description}
        actions={<>
          <Button variant="secondary" onClick={() => exportFile("CSV")} disabled={!data}><Download aria-hidden className="h-4 w-4" /> CSV</Button>
          <Button variant="secondary" onClick={() => exportFile("XLSX")} disabled={!data}><Download aria-hidden className="h-4 w-4" /> Excel</Button>
        </>} />
      {f.includePersonal === "true" && <Alert tone="warning" title="Personal details included" className="mb-4">This view and any export of it are recorded in the audit trail. Only records you may see personal details of show them.</Alert>}
      <Card>
        <FilterBar trailing={personalAllowed ? (
          <span className="flex items-center gap-2 text-sm text-fg">
            <Switch label="Include personal details" checked={f.includePersonal === "true"} onCheckedChange={(v) => update({ includePersonal: v ? "true" : null })} />
            <span aria-hidden>Include personal details</span>
          </span>
        ) : undefined}>
          {has("dateRange") && <>
            <Field label={`${def?.dateLabel ?? "Date"} from`}>{(p) => <Input {...p} type="date" value={f.from} max={f.to || undefined} onChange={(e) => update({ from: e.target.value })} />}</Field>
            <Field label="to">{(p) => <Input {...p} type="date" value={f.to} min={f.from || undefined} onChange={(e) => update({ to: e.target.value })} />}</Field>
          </>}
          {has("orgUnitId") && <Field label="Organization unit">{(p) => (
            <Select {...p} value={f.orgUnitId} onChange={(e) => update({ orgUnitId: e.target.value })}>
              <option value="">All I can see</option><UnitOptions permission={def?.permission ?? "student.read"} allowOrganization={false} />
            </Select>)}</Field>}
          {has("academicYearId") && <Field label="Academic year">{(p) => (
            <Select {...p} value={f.academicYearId} onChange={(e) => update({ academicYearId: e.target.value, batchId: null })}>
              <option value="">All</option>{years.data?.items?.map((y) => <option key={y.id} value={y.id}>{y.name}</option>)}
            </Select>)}</Field>}
          {has("programId") && <Field label={labels.singular("PROGRAM")}>{(p) => (
            <Select {...p} value={f.programId} onChange={(e) => update({ programId: e.target.value, courseId: null, batchId: null })}>
              <option value="">All</option>{programs.data?.items?.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </Select>)}</Field>}
          {has("courseId") && <Field label={labels.singular("COURSE")}>{(p) => (
            <Select {...p} value={f.courseId} onChange={(e) => update({ courseId: e.target.value, batchId: null })}>
              <option value="">All</option>{courses.data?.items?.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </Select>)}</Field>}
          {has("batchId") && <Field label={labels.singular("BATCH")}>{(p) => (
            <Select {...p} value={f.batchId} onChange={(e) => update({ batchId: e.target.value })}>
              <option value="">All</option>{batches.data?.items?.map((x) => <option key={x.id} value={x.id}>{x.code}</option>)}
            </Select>)}</Field>}
          {(def?.statuses?.length ?? 0) > 0 && <Field label="Status">{(p) => (
            <Select {...p} value={f.status} onChange={(e) => update({ status: e.target.value })}>
              <option value="">All</option>{def!.statuses!.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
            </Select>)}</Field>}
        </FilterBar>
        <DataTable caption={def?.title ?? "Report"} columns={columns} rows={rows} rowKey={(r) => String(r.i)}
          loading={catalogue.isPending || result.isPending} error={result.isError ? errorInfo(result.error) : null} onRetry={() => void result.refetch()}
          sort={f.sort ? { key: f.sort, direction: f.direction === "desc" ? "desc" : "asc" } : undefined}
          onSortChange={(s) => update({ sort: s.key, direction: s.direction })}
          page={data ? { page, totalPages: data.totalPages ?? 0, totalElements: data.totalElements ?? 0, onPageChange: (n) => update({ page: String(n) }) } : undefined}
          empty={{ title: "No matching records", description: "Change or clear the filters." }} />
      </Card>
    </>
  );
}

export default ReportPage;
