import { unwrap, type StudentImportJob } from "@ivorovi/api-client";
import { Alert, Button, ButtonLink, Card, CardBody, ConfirmDialog, KpiCard, useToast } from "@ivorovi/ui";
import { useMutation } from "@tanstack/react-query";
import { CheckCircle2, Hourglass, SkipForward, UserCog, UserPlus, XCircle } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { formatDateTime, formatNumber } from "../../../lib/format";
import { isFinished, MODE_TEXT, percent, type ImportMode } from "./importModel";
import { ImportJobFrame, JobStatusBadge, ProgressBar, stepPath, useRefreshImports } from "./ImportShared";

/** Import progress: live counts while the background job runs; cancellation where safe. */
export function ImportProgressPage() {
  return (
    <ImportJobFrame step="progress" title="Import progress" description="The import runs in the background. You can leave this page and come back later.">
      {(job) => <Progress job={job} />}
    </ImportJobFrame>
  );
}

function Progress({ job }: { job: StudentImportJob }) {
  const api = useApi();
  const toast = useToast();
  const refresh = useRefreshImports();
  const [confirming, setConfirming] = useState(false);
  const p = job.progress ?? {};
  const pct = isFinished(job.status) ? 100 : percent(p.processed, p.total);
  const cancel = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/student-imports/{id}/cancel", { params: { path: { id: job.id! } } })),
    onSuccess: () => { refresh(); setConfirming(false); toast.show("success", "Cancellation requested"); },
    onError: (e) => toast.show("danger", "Could not cancel", errorInfo(e).message),
  });
  const mode = job.mode as ImportMode | undefined;
  return (
    <div className="space-y-6">
      <Card>
        <CardBody className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="font-medium text-fg">
              {job.status === "QUEUED" ? "Waiting for a worker…" : job.status === "PROCESSING" ? `Importing row ${formatNumber(p.processed)} of ${formatNumber(p.total)}`
                : `Finished: ${formatNumber((p.imported ?? 0) + (p.updated ?? 0))} of ${formatNumber(p.total)} row(s) imported`}
            </p>
            <JobStatusBadge status={job.status} />
          </div>
          <ProgressBar value={pct} label="Import progress" />
          <p className="text-sm text-muted">
            {pct}% · {mode ? MODE_TEXT[mode].title : ""}{job.startedAt ? ` · started ${formatDateTime(job.startedAt)}` : ""}{job.attempts && job.attempts > 1 ? ` · attempt ${job.attempts}` : ""}
          </p>
          {job.cancelRequested && <Alert tone="warning">Cancellation requested; the import stops after the current rows{mode === "ALL_OR_NOTHING" ? " and everything it created is removed" : ""}.</Alert>}
        </CardBody>
      </Card>
      <section aria-label="Import counts" className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <KpiCard testId="g-processed" icon={Hourglass} label="Processed rows" value={formatNumber(p.processed)} secondary={`of ${formatNumber(p.total)}`} />
        <KpiCard testId="g-imported" icon={UserPlus} label="Students created" value={formatNumber(p.imported)} />
        <KpiCard testId="g-updated" icon={UserCog} label="Existing students updated" value={formatNumber(p.updated)} />
        <KpiCard testId="g-failed" icon={XCircle} label="Failed rows" value={formatNumber(p.failed)} />
        <KpiCard testId="g-skipped" icon={SkipForward} label="Skipped rows" value={formatNumber(p.skipped)} />
      </section>
      <div className="flex flex-wrap justify-end gap-3">
        {job.can?.cancel && <Button variant="danger" onClick={() => setConfirming(true)}>Cancel import</Button>}
        {isFinished(job.status) && <ButtonLink variant="primary" to={stepPath(job.id!, "result")}><CheckCircle2 aria-hidden className="h-4 w-4" /> View result</ButtonLink>}
      </div>
      <ConfirmDialog open={confirming} onOpenChange={setConfirming} title="Cancel this import?" confirmLabel="Cancel import" tone="danger" busy={cancel.isPending}
        description={mode === "ALL_OR_NOTHING" ? "All-or-nothing: every record this import created so far is removed again."
          : "Rows imported so far are kept; the remaining rows are not processed and are listed in the result report."}
        onConfirm={() => cancel.mutate()} />
    </div>
  );
}

export default ImportProgressPage;
