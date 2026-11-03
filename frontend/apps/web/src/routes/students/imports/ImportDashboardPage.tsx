import { unwrap, type StudentImportSummary } from "@ivorovi/api-client";
import { ButtonLink, Card, CardHeader, DataTable, ErrorState, KpiCard, Link, PageHeader, type Column } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, FileSpreadsheet, History, Hourglass, ListTodo, Settings2, Upload, UserPlus } from "lucide-react";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { formatDateTime, formatNumber } from "../../../lib/format";
import { formatBytes, isRunning, percent } from "./importModel";
import { JobStatusBadge, ProgressBar } from "./ImportShared";

/** Student import dashboard: work in progress, imports awaiting a decision, recent results and limits. */
export function ImportDashboardPage() {
  const api = useApi();
  const q = useQuery({ queryKey: ["student-imports", "overview"], refetchInterval: (s) => (s.state.data?.recent?.some((r) => isRunning(r.status)) ? 3000 : false),
    queryFn: async () => unwrap(await api.GET("/api/v1/student-imports/overview")) });
  const k = q.data;
  const n = (v: number | undefined) => formatNumber(v ?? 0);
  return (
    <>
      <PageHeader title="Student imports" description="Migrate existing student records from Excel (.xlsx) or CSV files: map columns, validate every row, resolve duplicates and import in the background."
        actions={<>
          <ButtonLink to="/students/imports/templates"><Settings2 aria-hidden className="h-4 w-4" /> Templates and rules</ButtonLink>
          <ButtonLink to="/students/imports/history"><History aria-hidden className="h-4 w-4" /> History</ButtonLink>
          <ButtonLink to="/students/imports/new" variant="primary"><Upload aria-hidden className="h-4 w-4" /> New import</ButtonLink>
        </>} />
      {q.isError ? <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card> : (
        <>
          <section aria-label="Import indicators" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <KpiCard testId="kpi-running" icon={Hourglass} label="In progress" value={n(k?.jobsInProgress)} secondary="Queued, validating or importing" loading={q.isPending}
              to="/students/imports/history?status=PROCESSING" />
            <KpiCard testId="kpi-awaiting" icon={ListTodo} label="Awaiting your action" value={n(k?.awaitingAction)} secondary="Mapping or confirmation needed" loading={q.isPending}
              to="/students/imports/history?status=READY" />
            <KpiCard testId="kpi-imported" icon={UserPlus} label="Students imported (30 days)" value={n(k?.studentsImportedLast30Days)}
              secondary={`${n(k?.completedLast30Days)} completed import(s)`} loading={q.isPending} />
            <KpiCard testId="kpi-failed" icon={AlertTriangle} label="Rows not imported (30 days)" value={n(k?.rowsFailedLast30Days)}
              secondary={`${n(k?.failedLast30Days)} failed import(s)`} loading={q.isPending} to="/students/imports/history?status=FAILED" />
          </section>
          <div className="mt-6 grid grid-cols-1 gap-6 xl:grid-cols-3">
            <Card className="xl:col-span-2">
              <CardHeader title="Recent imports" actions={<ButtonLink to="/students/imports/history" size="sm" variant="ghost">View all</ButtonLink>} />
              <RecentImports rows={k?.recent} loading={q.isPending} />
            </Card>
            <Card>
              <CardHeader title="How it works" />
              <ol className="space-y-4 p-5 text-sm sm:p-6">
                {[
                  ["Download a template", "Standard templates list every column with formats and your academic codes."],
                  ["Upload the file", "Excel .xlsx or CSV. Macros and formulas are never executed; files are malware-scanned."],
                  ["Map and validate", "Columns are matched automatically; ambiguous ones are left for you. Every row is checked."],
                  ["Resolve duplicates", "Possible duplicates need a decision. Students are never merged automatically."],
                  ["Confirm and import", "Choose all-or-nothing or valid rows only. The import runs in the background."],
                ].map(([t, d], i) => (
                  <li key={t} className="flex gap-3">
                    <span aria-hidden className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-soft text-xs font-semibold text-primary">{i + 1}</span>
                    <span><span className="block font-medium text-fg">{t}</span><span className="text-muted">{d}</span></span>
                  </li>
                ))}
              </ol>
              {k?.limits && (
                <p className="border-t border-border px-5 py-4 text-xs text-muted sm:px-6">
                  Limits: {formatBytes(k.limits.maxFileBytes)} and {formatNumber(k.limits.maxRows)} rows per file, {k.limits.maxColumns} columns.
                  Source files and raw values are deleted {k.limits.retentionDays} days after an import finishes.
                </p>
              )}
            </Card>
          </div>
        </>
      )}
    </>
  );
}

function RecentImports({ rows, loading }: { rows: StudentImportSummary[] | undefined; loading: boolean }) {
  const columns: Column<StudentImportSummary>[] = [
    { key: "file", header: "File", cell: (r) => (
      <span className="block min-w-0">
        <Link to={`/students/imports/${r.id}`} className="flex min-w-0 items-center gap-2 font-medium hover:underline">
          <FileSpreadsheet aria-hidden className="h-4 w-4 shrink-0 text-muted" /><span className="truncate">{r.fileName}</span>
        </Link>
        <span className="block truncate text-xs text-muted">{r.institutionName} · {formatDateTime(r.createdAt)}</span>
      </span>) },
    { key: "status", header: "Status", cell: (r) => (
      <span className="block min-w-28">
        <JobStatusBadge status={r.status} />
        {r.status === "PROCESSING" && <span className="mt-2 block"><ProgressBar value={percent(r.processedRows, r.progressTotal)} label={`Progress of ${r.fileName}`} /></span>}
      </span>) },
    { key: "rows", header: "Rows", hideOnMobile: true, cell: (r) => (
      <span className="tabular inline-flex items-center gap-3 text-sm">
        <span>{formatNumber(r.totalRows)}</span>
        {(r.succeededRows ?? 0) > 0 && <span className="inline-flex items-center gap-1 text-success-text"><CheckCircle2 aria-hidden className="h-3.5 w-3.5" />{formatNumber(r.succeededRows)}</span>}
        {(r.failedRows ?? 0) > 0 && <span className="inline-flex items-center gap-1 text-danger-text"><AlertTriangle aria-hidden className="h-3.5 w-3.5" />{formatNumber(r.failedRows)}</span>}
      </span>) },
  ];
  return <DataTable caption="Recent imports" columns={columns} rows={rows} rowKey={(r) => r.id!} loading={loading}
    empty={{ title: "No imports yet", description: "Start with a template, then upload your first file.", action: <ButtonLink to="/students/imports/new" variant="primary">New import</ButtonLink> }} />;
}

export default ImportDashboardPage;
