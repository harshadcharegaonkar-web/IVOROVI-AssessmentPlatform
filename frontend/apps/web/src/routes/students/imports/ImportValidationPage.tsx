import type { StudentImportJob, StudentImportRow } from "@ivorovi/api-client";
import { Alert, Button, ButtonLink, Card, CardBody, CardHeader, KpiCard, useToast, type Column } from "@ivorovi/ui";
import { useMutation } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Copy, Download, FileWarning, RotateCw, Split, XCircle } from "lucide-react";
import { useApi, errorInfo } from "../../../api/ApiProvider";
import { formatNumber } from "../../../lib/format";
import { percent } from "./importModel";
import { ImportJobFrame, ProgressBar, stepPath, useRefreshImports, useReportDownload } from "./ImportShared";
import { IssueList, MatchBadge, RowIdentity, RowsPanel, RowStatusBadge } from "./ImportRows";

/** Validation: background progress, then counts and every row with issues (row and column references). */
export function ImportValidationPage() {
  return (
    <ImportJobFrame step="validation" title="Validation" description="Every row is checked before anything is imported: formats, required values, academic references and duplicates.">
      {(job) => <Validation job={job} />}
    </ImportJobFrame>
  );
}

function Validation({ job }: { job: StudentImportJob }) {
  const api = useApi();
  const toast = useToast();
  const refresh = useRefreshImports();
  const report = useReportDownload(job);
  const c = job.counts ?? {};
  const retry = useMutation({
    mutationFn: async () => (await api.POST("/api/v1/student-imports/{id}/retry", { params: { path: { id: job.id! } } })).data,
    onSuccess: () => { refresh(); toast.show("success", "Validation queued again"); },
    onError: (e) => toast.show("danger", "Could not retry", errorInfo(e).message),
  });
  if (job.status === "QUEUED" || job.status === "VALIDATING") {
    const pct = percent(job.progress?.processed, job.counts?.total);
    return (
      <Card>
        <CardBody className="space-y-4">
          <p className="font-medium text-fg">{job.status === "QUEUED" ? "Waiting for a worker…" : `Validating row ${formatNumber(job.progress?.processed)} of ${formatNumber(job.counts?.total)}`}</p>
          <ProgressBar value={pct} label="Validation progress" />
          <p className="text-sm text-muted">You can leave this page; validation continues in the background.</p>
        </CardBody>
      </Card>
    );
  }
  if (job.status === "FAILED" && job.phase !== "IMPORT") {
    return (
      <Alert tone="danger" title="Validation failed">
        <span className="block">{job.failureMessage}</span>
        <span className="mt-3 flex flex-wrap gap-3">
          {job.can?.retry && <Button size="sm" loading={retry.isPending} onClick={() => retry.mutate()}><RotateCw aria-hidden className="h-4 w-4" /> Retry</Button>}
          <ButtonLink size="sm" to="/students/imports/new">Upload a corrected file</ButtonLink>
        </span>
      </Alert>
    );
  }
  if (job.status === "UPLOADED") return <Alert tone="info">Map the columns first. <ButtonLink to={stepPath(job.id!, "mapping")} size="sm" variant="ghost">Map columns</ButtonLink></Alert>;
  const columns: Column<StudentImportRow>[] = [
    { key: "row", header: "Row", cell: (r) => <RowIdentity row={r} /> },
    { key: "status", header: "Result", cell: (r) => <span className="flex flex-col items-start gap-1"><RowStatusBadge row={r} /><MatchBadge category={r.matchCategory} /></span> },
    { key: "issues", header: "Issues", cell: (r) => <IssueList row={r} /> },
  ];
  return (
    <div className="space-y-6">
      <section aria-label="Validation results" className="grid grid-cols-2 gap-4 lg:grid-cols-6">
        <KpiCard testId="v-total" icon={FileWarning} label="Rows" value={formatNumber(c.total)} />
        <KpiCard testId="v-valid" icon={CheckCircle2} label="Valid" value={formatNumber(c.valid)} />
        <KpiCard testId="v-invalid" icon={XCircle} label="Invalid" value={formatNumber(c.invalid)} />
        <KpiCard testId="v-warnings" icon={AlertTriangle} label="With warnings" value={formatNumber(c.warnings)} />
        <KpiCard testId="v-duplicates" icon={Copy} label="Duplicates" value={formatNumber(c.duplicates)} secondary={`${formatNumber(c.potentialDuplicates)} need a decision`} />
        <KpiCard testId="v-conflicts" icon={Split} label="Conflicts" value={formatNumber(c.conflicts)} />
      </section>
      <Card>
        <CardHeader title="Rows with issues" description="Correct the source spreadsheet and upload it again, or continue: rows with errors are not imported."
          actions={<div className="flex flex-wrap gap-2">
            <Button size="sm" onClick={() => void report("validation")}><Download aria-hidden className="h-4 w-4" /> Validation report</Button>
            {job.can?.viewValues && (c.invalid ?? 0) + (c.conflicts ?? 0) > 0 && <Button size="sm" onClick={() => void report("corrections", "XLSX")}><Download aria-hidden className="h-4 w-4" /> Rows to correct (.xlsx)</Button>}
          </div>} />
        <div className="px-5 pt-4 sm:px-6">
          <RowsPanel job={job} initial="ISSUES" caption="Validated rows" empty="No rows in this view"
            tabs={[{ value: "ISSUES", label: "With issues" }, { value: "ERRORS", label: "Errors", count: (c.invalid ?? 0) + (c.conflicts ?? 0) },
              { value: "WARNINGS", label: "Warnings", count: c.warnings }, { value: "DUPLICATES", label: "Duplicates", count: (c.duplicates ?? 0) + (c.potentialDuplicates ?? 0) },
              { value: "VALID", label: "Valid", count: c.valid }, { value: "ALL", label: "All rows", count: c.total }]}
            columns={columns} />
        </div>
      </Card>
      {job.status === "READY" && (
        <div className="flex flex-wrap justify-end gap-3">
          <ButtonLink to={stepPath(job.id!, "mapping")}>Change mapping</ButtonLink>
          <ButtonLink variant="primary" to={stepPath(job.id!, (c.unresolved ?? 0) > 0 ? "duplicates" : "preview")}>
            {(c.unresolved ?? 0) > 0 ? `Resolve ${c.unresolved} possible duplicate(s)` : "Continue to preview"}
          </ButtonLink>
        </div>
      )}
    </div>
  );
}

export default ImportValidationPage;
