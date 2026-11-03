import type { StudentImportJob } from "@ivorovi/api-client";
import { Alert, Button, ButtonLink, Card, CardHeader, KpiCard, cn } from "@ivorovi/ui";
import { AlertTriangle, CheckCircle2, Copy, Download, FileStack, ListChecks, Split, UserCog, UserPlus, XCircle } from "lucide-react";
import { useState } from "react";
import { formatNumber } from "../../../lib/format";
import { MODE_TEXT, type ImportMode } from "./importModel";
import { ImportJobFrame, stepPath, useReportDownload } from "./ImportShared";

/** Import preview: what will be created, what existing records are affected, and the choice of mode. */
export function ImportPreviewPage() {
  return (
    <ImportJobFrame step="preview" title="Preview" description="Review exactly what this import will do. Nothing has been imported yet.">
      {(job) => <Preview job={job} />}
    </ImportJobFrame>
  );
}

export function blockers(job: StudentImportJob, mode: ImportMode): string[] {
  const c = job.counts ?? {};
  const out: string[] = [];
  if ((c.unresolved ?? 0) > 0) out.push(`${c.unresolved} possible duplicate(s) still need a decision.`);
  if (mode === "ALL_OR_NOTHING" && (c.invalid ?? 0) + (c.conflicts ?? 0) > 0) {
    out.push(`All or nothing needs a file without invalid or conflicting rows (${(c.invalid ?? 0) + (c.conflicts ?? 0)} found).`);
  }
  if ((c.toCreate ?? 0) + (c.toUpdate ?? 0) === 0) out.push("No row would be imported.");
  return out;
}

function Preview({ job }: { job: StudentImportJob }) {
  const c = job.counts ?? {};
  const clean = (c.invalid ?? 0) + (c.conflicts ?? 0) === 0;
  const [mode, setMode] = useState<ImportMode>(clean ? "ALL_OR_NOTHING" : "VALID_ROWS_ONLY");
  const report = useReportDownload(job);
  const problems = blockers(job, mode);
  if (job.status !== "READY") {
    return <Alert tone="info">This import was already confirmed. <ButtonLink to={stepPath(job.id!, job.status === "PROCESSING" || job.status === "QUEUED" ? "progress" : "result")} size="sm" variant="ghost">View progress</ButtonLink></Alert>;
  }
  const notImported = Math.max(0, (c.total ?? 0) - (c.toCreate ?? 0) - (c.toUpdate ?? 0));
  return (
    <div className="space-y-6">
      <section aria-label="Import preview" className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <KpiCard testId="p-total" icon={FileStack} label="Uploaded rows" value={formatNumber(c.total)} />
        <KpiCard testId="p-valid" icon={CheckCircle2} label="Valid rows" value={formatNumber(c.valid)} secondary={`${formatNumber(c.warnings)} with warnings`} />
        <KpiCard testId="p-invalid" icon={XCircle} label="Invalid rows" value={formatNumber(c.invalid)} secondary={`${formatNumber(c.conflicts)} conflicting`} />
        <KpiCard testId="p-duplicates" icon={Copy} label="Duplicate rows" value={formatNumber(c.duplicates)} secondary={`${formatNumber(c.potentialDuplicates)} possible duplicates`} />
        <KpiCard testId="p-create" icon={UserPlus} label="Students to create" value={formatNumber(c.toCreate)} />
        <KpiCard testId="p-update" icon={UserCog} label="Existing students affected" value={formatNumber(c.toUpdate)} secondary="Admission, enrolment or guardians added" />
        <KpiCard testId="p-skipped" icon={Split} label="Rows not imported" value={formatNumber(notImported)} secondary="Invalid, conflicting, duplicate or skipped" />
        <KpiCard testId="p-warnings" icon={AlertTriangle} label="Warnings" value={formatNumber(c.warnings)} />
      </section>
      <Card>
        <CardHeader title="Import mode" description="How failures during the import are handled." />
        <div role="radiogroup" aria-label="Import mode" className="grid grid-cols-1 gap-4 px-5 pb-5 sm:px-6 md:grid-cols-2">
          {(["ALL_OR_NOTHING", "VALID_ROWS_ONLY"] as ImportMode[]).map((m) => (
            <button key={m} type="button" role="radio" aria-checked={mode === m} onClick={() => setMode(m)}
              className={cn("rounded-lg border p-4 text-left transition-colors", mode === m ? "border-primary bg-primary-soft" : "border-border hover:bg-surface-muted")}>
              <span className="flex items-center gap-2 font-semibold text-fg"><ListChecks aria-hidden className="h-4 w-4" />{MODE_TEXT[m].title}</span>
              <span className="mt-1 block text-sm text-muted-strong">{MODE_TEXT[m].description}</span>
            </button>
          ))}
        </div>
      </Card>
      {problems.length > 0 && <Alert tone="warning" title="Cannot continue yet">{problems.map((p) => <span key={p} className="block">{p}</span>)}</Alert>}
      <div className="flex flex-wrap justify-end gap-3">
        <ButtonLink to={stepPath(job.id!, "validation")}>Back to validation</ButtonLink>
        <Button onClick={() => void report("validation")}><Download aria-hidden className="h-4 w-4" /> Validation report</Button>
        <ButtonLink variant="primary" to={`${stepPath(job.id!, "confirm")}?mode=${mode}`} aria-disabled={problems.length > 0}
          onClick={(e) => { if (problems.length > 0) e.preventDefault(); }}>Review and confirm</ButtonLink>
      </div>
    </div>
  );
}

export default ImportPreviewPage;
