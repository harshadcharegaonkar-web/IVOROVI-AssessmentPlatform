import { unwrap, type StudentImportJob } from "@ivorovi/api-client";
import { Alert, Button, ButtonLink, Card, CardBody, CardHeader } from "@ivorovi/ui";
import { useMutation } from "@tanstack/react-query";
import { PlayCircle } from "lucide-react";
import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../../api/ApiProvider";
import { formatNumber } from "../../../lib/format";
import { MODE_TEXT, type ImportMode } from "./importModel";
import { ImportJobFrame, stepPath, useRefreshImports } from "./ImportShared";
import { blockers } from "./ImportPreviewPage";

/** Explicit confirmation: summary of the effect, acknowledgment, then the import is queued. */
export function ImportConfirmPage() {
  return (
    <ImportJobFrame step="confirm" title="Confirm import" description="The import starts only after this confirmation. It runs in the background; you can follow its progress.">
      {(job) => <Confirm job={job} />}
    </ImportJobFrame>
  );
}

function Confirm({ job }: { job: StudentImportJob }) {
  const api = useApi();
  const navigate = useNavigate();
  const refresh = useRefreshImports();
  const [params] = useSearchParams();
  const mode = (params.get("mode") === "ALL_OR_NOTHING" ? "ALL_OR_NOTHING" : "VALID_ROWS_ONLY") as ImportMode;
  const [ack, setAck] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const c = job.counts ?? {};
  const expected = (c.toCreate ?? 0) + (c.toUpdate ?? 0);
  const problems = job.status === "READY" ? blockers(job, mode) : ["This import was already confirmed or has changed."];
  const confirm = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/student-imports/{id}/confirm", { params: { path: { id: job.id! } },
        body: { mode, version: job.version!, expectedRows: expected, acknowledged: ack } })),
    onSuccess: (j) => { refresh(); navigate(stepPath(j.id!, "progress")); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const notImported = Math.max(0, (c.total ?? 0) - expected);
  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <Card>
        <CardHeader title="What will happen" />
        <CardBody>
          <ul className="space-y-3 text-sm">
            <li><span className="font-semibold text-fg tabular">{formatNumber(c.toCreate)}</span> new student profile(s) will be created in <span className="font-semibold">{job.institutionName}</span>, with their admissions, enrolments and guardians from the file.</li>
            <li><span className="font-semibold text-fg tabular">{formatNumber(c.toUpdate)}</span> existing student(s) will receive the admission, enrolment or guardian records of their row. Their profile details are not changed.</li>
            <li><span className="font-semibold text-fg tabular">{formatNumber(notImported)}</span> row(s) will not be imported (invalid, conflicting, duplicate or skipped by decision) and are listed in the result report.</li>
            <li>Mode: <span className="font-semibold">{MODE_TEXT[mode].title}</span> — {MODE_TEXT[mode].description}</li>
            <li>Every created record and this confirmation are written to the audit trail under your name.</li>
          </ul>
        </CardBody>
      </Card>
      {problems.length > 0 && <Alert tone="warning" title="Cannot confirm">{problems.map((p) => <span key={p} className="block">{p}</span>)}</Alert>}
      {problem && <Alert tone="danger">{problem}</Alert>}
      <Card>
        <CardBody className="space-y-4">
          <label className="flex items-start gap-3 text-sm">
            <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[var(--color-primary)]" checked={ack} onChange={(e) => setAck(e.target.checked)} />
            <span>I reviewed the preview and confirm the import of {formatNumber(expected)} row(s) into {job.institutionName}.</span>
          </label>
          <div className="flex flex-wrap justify-end gap-3">
            <ButtonLink to={stepPath(job.id!, "preview")}>Back to preview</ButtonLink>
            <Button variant="primary" disabled={!ack || problems.length > 0 || !job.can?.confirm} loading={confirm.isPending}
              onClick={() => { setProblem(null); confirm.mutate(); }}><PlayCircle aria-hidden className="h-4 w-4" /> Start import</Button>
          </div>
        </CardBody>
      </Card>
    </div>
  );
}

export default ImportConfirmPage;
