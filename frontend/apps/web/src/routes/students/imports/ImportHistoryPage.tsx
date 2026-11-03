import { unwrap, type StudentImportSummary } from "@ivorovi/api-client";
import { Breadcrumbs, ButtonLink, Card, DataTable, FilterBar, Input, Link, PageHeader, Select, type Column } from "@ivorovi/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { FileSpreadsheet, Upload } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { formatDateTime, formatNumber } from "../../../lib/format";
import { useOrgLabels } from "../../../session/useOrgLabels";
import { UnitChoices } from "../../academics/AcademicsShared";
import { isRunning, jobStatusText, MODE_TEXT, type ImportMode, type JobStatus } from "./importModel";
import { JobStatusBadge } from "./ImportShared";

const STATUSES: JobStatus[] = ["UPLOADED", "QUEUED", "VALIDATING", "READY", "PROCESSING", "COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED"];

/** Import history: every import of the institutions the caller may import into. */
export function ImportHistoryPage() {
  const api = useApi();
  const labels = useOrgLabels();
  const [params, setParams] = useSearchParams();
  const status = params.get("status") ?? "";
  const institutionId = params.get("institutionId") ?? "";
  const [q, setQ] = useState(params.get("q") ?? "");
  const page = Number(params.get("page") ?? 0);
  const set = (k: string, v: string) => setParams((p) => { const n = new URLSearchParams(p); if (v) n.set(k, v); else n.delete(k); if (k !== "page") n.delete("page"); return n; });
  const list = useQuery({
    queryKey: ["student-imports", "history", status, institutionId, params.get("q") ?? "", page], placeholderData: keepPreviousData,
    refetchInterval: (s) => (s.state.data?.items?.some((r) => isRunning(r.status)) ? 3000 : false),
    queryFn: async () => unwrap(await api.GET("/api/v1/student-imports", { params: { query: {
      ...(status ? { status: status as JobStatus } : {}), ...(institutionId ? { institutionId } : {}), ...(params.get("q") ? { q: params.get("q")! } : {}), page, size: 25 } } })),
  });
  const columns: Column<StudentImportSummary>[] = [
    { key: "id", header: "Import", cell: (r) => (
      <span className="block min-w-0">
        <Link to={`/students/imports/${r.id}`} className="flex min-w-0 items-center gap-2 font-medium hover:underline">
          <FileSpreadsheet aria-hidden className="h-4 w-4 shrink-0 text-muted" /><span className="truncate">{r.fileName}</span></Link>
        <span className="block truncate font-mono text-xs text-muted" title={r.id}>{r.id?.slice(0, 8)}</span>
      </span>) },
    { key: "institution", header: labels.singular("COLLEGE"), hideOnMobile: true, cell: (r) => <span className="block truncate">{r.institutionName}</span> },
    { key: "uploaded", header: "Uploaded", hideOnMobile: true, cell: (r) => <span className="block min-w-0"><span className="block">{formatDateTime(r.createdAt)}</span>
      <span className="block truncate text-xs text-muted">{r.createdByName}</span></span> },
    { key: "mode", header: "Mode", hideOnMobile: true, cell: (r) => r.mode ? MODE_TEXT[r.mode as ImportMode].title : <span className="text-muted">—</span> },
    { key: "rows", header: "Rows", cell: (r) => (
      <span className="tabular block text-sm">
        <span className="block">{formatNumber(r.totalRows)} total</span>
        <span className="block text-xs text-muted">{formatNumber(r.succeededRows)} imported · {formatNumber(r.failedRows)} failed · {formatNumber(r.skippedRows)} skipped</span>
      </span>) },
    { key: "status", header: "Status", cell: (r) => <JobStatusBadge status={r.status} /> },
  ];
  return (
    <>
      <Breadcrumbs items={[{ label: "Students", to: "/students" }, { label: "Imports", to: "/students/imports" }, { label: "History" }]} />
      <PageHeader title="Import history" description="Every student import of the institutions you can import into, with its outcome and reports."
        actions={<ButtonLink to="/students/imports/new" variant="primary"><Upload aria-hidden className="h-4 w-4" /> New import</ButtonLink>} />
      <Card>
        <FilterBar>
          <Input aria-label="Search by file name or import ID" placeholder="File name or import ID" value={q} onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") set("q", q.trim()); }} onBlur={() => set("q", q.trim())} className="sm:w-64" />
          <Select aria-label="Status" value={status} onChange={(e) => set("status", e.target.value)} className="sm:w-52">
            <option value="">All statuses</option>{STATUSES.map((s) => <option key={s} value={s}>{jobStatusText(s)}</option>)}
          </Select>
          <Select aria-label="Institution" value={institutionId} onChange={(e) => set("institutionId", e.target.value)} className="sm:w-64">
            <option value="">All institutions</option><UnitChoices permission="student.import" types={["UNIVERSITY", "COLLEGE"]} />
          </Select>
        </FilterBar>
        <DataTable caption="Import history" columns={columns} rows={list.data?.items} rowKey={(r) => r.id!} loading={list.isPending}
          error={list.isError ? errorInfo(list.error) : null} onRetry={() => void list.refetch()}
          page={list.data ? { page, totalPages: list.data.totalPages ?? 0, totalElements: list.data.totalElements ?? 0, onPageChange: (n) => set("page", String(n)) } : undefined}
          empty={{ title: "No imports found", description: status || institutionId || params.get("q") ? "Change the filters." : "Imports you start appear here." }} />
      </Card>
    </>
  );
}

export default ImportHistoryPage;
