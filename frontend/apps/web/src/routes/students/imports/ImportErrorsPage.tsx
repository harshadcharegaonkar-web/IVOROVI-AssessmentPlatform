import type { StudentImportJob, StudentImportRow } from "@ivorovi/api-client";
import { Alert, Button, Card, CardHeader, type Column } from "@ivorovi/ui";
import { Download, FileSpreadsheet } from "lucide-react";
import { formatNumber } from "../../../lib/format";
import { ImportJobFrame, REPORT_TEXT, useReportDownload } from "./ImportShared";
import { IssueList, MatchBadge, OutcomeBadge, RowIdentity, RowsPanel } from "./ImportRows";

/** Error report: every error with row, column, category, description and recommended correction; downloadable. */
export function ImportErrorsPage() {
  return (
    <ImportJobFrame step="result" title="Error report" description="Every problem with its row, column and the recommended correction. Fix the source spreadsheet and upload it again.">
      {(job) => <Errors job={job} />}
    </ImportJobFrame>
  );
}

function Errors({ job }: { job: StudentImportJob }) {
  const report = useReportDownload(job);
  const c = job.counts ?? {};
  const p = job.progress ?? {};
  const columns: Column<StudentImportRow>[] = [
    { key: "row", header: "Row", cell: (r) => <RowIdentity row={r} /> },
    { key: "problem", header: "Problem and correction", cell: (r) => (
      <span className="block min-w-0 space-y-2">
        <span className="flex flex-wrap gap-2"><MatchBadge category={r.matchCategory} />{r.outcome && r.outcome !== "PENDING" && <OutcomeBadge outcome={r.outcome} />}</span>
        <IssueList row={r} />
        {r.outcome === "FAILED" && <span className="block text-sm text-fg">{r.outcomeMessage}</span>}
      </span>) },
  ];
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader title="Download" description="The error report lists, for every problem: original row number, column name, error category, description and recommended correction." />
        <div className="flex flex-wrap gap-3 px-5 pb-5 sm:px-6">
          <Button onClick={() => void report("errors", "XLSX")}><Download aria-hidden className="h-4 w-4" /> {REPORT_TEXT.errors.label} (.xlsx)</Button>
          <Button onClick={() => void report("errors", "CSV")}><Download aria-hidden className="h-4 w-4" /> {REPORT_TEXT.errors.label} (.csv)</Button>
          {job.can?.viewValues && job.sourceAvailable && (
            <Button onClick={() => void report("corrections", "XLSX")}><FileSpreadsheet aria-hidden className="h-4 w-4" /> {REPORT_TEXT.corrections.label} (.xlsx)</Button>
          )}
        </div>
      </Card>
      {(c.invalid ?? 0) + (c.conflicts ?? 0) + (p.failed ?? 0) === 0 && <Alert tone="success">No errors: every row passed validation{p.failed === 0 && job.phase === "IMPORT" ? " and imported" : ""}.</Alert>}
      <Card>
        <div className="px-5 pt-4 sm:px-6">
          <RowsPanel job={job} initial="ERRORS" caption="Rows with errors" empty="No rows in this view"
            tabs={[{ value: "ERRORS", label: "Validation errors", count: (c.invalid ?? 0) + (c.conflicts ?? 0) }, { value: "FAILED", label: "Import failures", count: p.failed },
              { value: "WARNINGS", label: "Warnings", count: c.warnings }]}
            columns={columns} />
        </div>
      </Card>
      <p className="text-xs text-muted-strong">{formatNumber(c.total)} rows checked. Reports never include values the file did not contain; the rows-to-correct file includes original values and its download is audited.</p>
    </div>
  );
}

export default ImportErrorsPage;
