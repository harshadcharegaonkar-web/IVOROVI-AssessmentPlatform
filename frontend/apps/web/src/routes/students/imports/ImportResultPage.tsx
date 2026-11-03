import { unwrap, type StudentImportJob, type StudentImportRow } from "@ivorovi/api-client";
import { Alert, Button, ButtonLink, Card, CardHeader, KpiCard, useToast, type Column } from "@ivorovi/ui";
import { useMutation } from "@tanstack/react-query";
import { Download, FileDown, RotateCw, SkipForward, UserCog, UserPlus, XCircle } from "lucide-react";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { formatDateTime, formatNumber } from "../../../lib/format";
import { MODE_TEXT, type ImportMode } from "./importModel";
import { ImportJobFrame, REPORT_TEXT, stepPath, useDownload, useRefreshImports, useReportDownload, type ReportKind } from "./ImportShared";
import { OutcomeBadge, RowIdentity, RowsPanel } from "./ImportRows";

/** Import result summary: outcome of every source row, downloads, retry where safe. */
export function ImportResultPage() {
  return (
    <ImportJobFrame step="result" title="Import result" description="The outcome of every row of the file. Download the reports to correct and re-upload rows that were not imported.">
      {(job) => <Result job={job} />}
    </ImportJobFrame>
  );
}

function Result({ job }: { job: StudentImportJob }) {
  const api = useApi();
  const toast = useToast();
  const refresh = useRefreshImports();
  const report = useReportDownload(job);
  const download = useDownload();
  const p = job.progress ?? {};
  const retry = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/student-imports/{id}/retry", { params: { path: { id: job.id! } } })),
    onSuccess: () => { refresh(); toast.show("success", "Import queued again", "Completed rows are kept."); },
    onError: (e) => toast.show("danger", "Could not retry", errorInfo(e).message),
  });
  const columns: Column<StudentImportRow>[] = [
    { key: "row", header: "Row", cell: (r) => <RowIdentity row={r} /> },
    { key: "outcome", header: "Outcome", cell: (r) => <OutcomeBadge outcome={r.outcome} /> },
    { key: "message", header: "Details", cell: (r) => <span className="block min-w-0 text-sm">{r.outcomeMessage ?? r.matchReason ?? ""}</span> },
  ];
  const tone = job.status === "COMPLETED" ? "success" : job.status === "PARTIALLY_COMPLETED" ? "warning" : job.status === "CANCELLED" ? "info" : "danger";
  const headings: Record<string, string> = { COMPLETED: "Import completed", PARTIALLY_COMPLETED: "Import partially completed", FAILED: "Import failed", CANCELLED: "Import cancelled" };
  const heading = headings[job.status ?? ""] ?? "Import in progress";
  return (
    <div className="space-y-6">
      <Alert tone={tone} title={heading}>
        <span className="block">
          {formatNumber(p.imported)} student(s) created and {formatNumber(p.updated)} updated; {formatNumber(p.failed)} failed and {formatNumber(p.skipped)} not imported.
          {job.mode ? ` Mode: ${MODE_TEXT[job.mode as ImportMode].title}.` : ""}{job.finishedAt ? ` Finished ${formatDateTime(job.finishedAt)}.` : ""}
        </span>
        {job.rolledBack && <span className="mt-1 block font-medium">All records created by this import were removed again (all-or-nothing).</span>}
        {job.failureMessage && <span className="mt-1 block">{job.failureMessage}</span>}
        {job.can?.retry && <span className="mt-3 block"><Button size="sm" loading={retry.isPending} onClick={() => retry.mutate()}><RotateCw aria-hidden className="h-4 w-4" /> Retry</Button></span>}
      </Alert>
      {(job.status === "QUEUED" || job.status === "PROCESSING") && <Alert tone="info">Still running. <ButtonLink to={stepPath(job.id!, "progress")} size="sm" variant="ghost">View progress</ButtonLink></Alert>}
      <section aria-label="Import result" className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <KpiCard testId="r-imported" icon={UserPlus} label="Students created" value={formatNumber(p.imported)} />
        <KpiCard testId="r-updated" icon={UserCog} label="Existing students updated" value={formatNumber(p.updated)} />
        <KpiCard testId="r-failed" icon={XCircle} label="Failed rows" value={formatNumber(p.failed)} />
        <KpiCard testId="r-skipped" icon={SkipForward} label="Not imported" value={formatNumber(p.skipped)} />
      </section>
      <Card>
        <CardHeader title="Downloads" description={job.sourceAvailable ? `Available until ${job.purgeAfter ? formatDateTime(job.purgeAfter) : "the retention period ends"}.` : "The original file and values were deleted after the retention period."} />
        <ul className="grid grid-cols-1 gap-3 px-5 pb-5 sm:px-6 md:grid-cols-2">
          {(["result", "errors", "validation", "corrections"] as ReportKind[]).filter((k) => k !== "corrections" || (job.can?.viewValues && job.sourceAvailable)).map((k) => (
            <li key={k} className="flex items-start justify-between gap-3 rounded-lg border border-border p-3">
              <span className="min-w-0"><span className="block font-medium text-fg">{REPORT_TEXT[k].label}</span><span className="block text-xs text-muted">{REPORT_TEXT[k].description}</span></span>
              <span className="flex shrink-0 gap-1">
                <Button size="sm" variant="ghost" aria-label={`${REPORT_TEXT[k].label} as CSV`} onClick={() => void report(k, "CSV")}>CSV</Button>
                <Button size="sm" variant="ghost" aria-label={`${REPORT_TEXT[k].label} as Excel`} onClick={() => void report(k, "XLSX")}>XLSX</Button>
              </span>
            </li>
          ))}
          {job.can?.downloadSource && (
            <li className="flex items-start justify-between gap-3 rounded-lg border border-border p-3">
              <span className="min-w-0"><span className="block font-medium text-fg">Original file</span><span className="block text-xs text-muted">The uploaded file as scanned (download is audited).</span></span>
              <Button size="sm" variant="ghost" aria-label="Download the original file" onClick={() => void download(() => api.GET("/api/v1/student-imports/{id}/source",
                { params: { path: { id: job.id! } }, parseAs: "blob" }), job.fileName ?? "import")}><FileDown aria-hidden className="h-4 w-4" /></Button>
            </li>
          )}
        </ul>
        <div className="flex flex-wrap gap-3 border-t border-border px-5 py-4 sm:px-6">
          <ButtonLink to={`/students/imports/${job.id}/errors`} size="sm"><Download aria-hidden className="h-4 w-4" /> View the error report</ButtonLink>
          <ButtonLink to="/students/imports/new" size="sm" variant="ghost">Upload a corrected file</ButtonLink>
        </div>
      </Card>
      <Card>
        <div className="px-5 pt-4 sm:px-6">
          <RowsPanel job={job} initial="ALL" caption="Row outcomes" empty="No rows in this view"
            tabs={[{ value: "ALL", label: "All rows", count: job.counts?.total }, { value: "IMPORTED", label: "Imported", count: (p.imported ?? 0) + (p.updated ?? 0) },
              { value: "FAILED", label: "Failed", count: p.failed }, { value: "SKIPPED", label: "Not imported", count: p.skipped }]}
            columns={columns} />
        </div>
      </Card>
    </div>
  );
}

export default ImportResultPage;
