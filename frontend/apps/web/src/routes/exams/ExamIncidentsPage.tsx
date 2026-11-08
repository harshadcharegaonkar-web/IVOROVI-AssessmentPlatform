import { unwrap, type ExamIncident } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, Input, Link, Modal, PageHeader, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, formatNumber, humanize } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { StatusBadge } from "../integrations/IntegrationShared";
import { ExamTabs, fromLocalInput, toLocalInput } from "./ExamShared";

/** Platform outages detected automatically, and outages reported by the institution (C-3). */
export function ExamIncidentsPage() {
  const api = useApi();
  const { access } = useSession();
  const [reporting, setReporting] = useState(false);
  const q = useQuery({ queryKey: ["exam-incidents"], refetchInterval: 30_000, queryFn: async () => unwrap(await api.GET("/api/v1/exam/incidents")) });
  const columns: Column<ExamIncident>[] = [
    { key: "what", header: "Incident", cell: (i) => <Link to={`/exams/incidents/${i.id}`} className="font-medium text-fg hover:underline">{i.description}</Link> },
    { key: "source", header: "Source", cell: (i) => (i.source === "PLATFORM" ? "Detected by the platform" : "Reported"), hideOnMobile: true },
    { key: "window", header: "Window", cell: (i) => <span className="text-sm">{formatDateTime(i.startedAt)} – {i.endedAt ? formatDateTime(i.endedAt) : "ongoing"}</span> },
    { key: "affected", header: "Affected candidates", cell: (i) => formatNumber(i.affected) },
    { key: "status", header: "Status", cell: (i) => <StatusBadge status={i.status} /> },
  ];
  return (
    <>
      <PageHeader title="Examinations" description="Time keeps running on the server during an interruption. Compensation is proposed here and applied only after a second person approves it."
        actions={access.has("exam.incident.manage") ? <Button variant="primary" onClick={() => setReporting(true)}>Report an outage</Button> : undefined} />
      <ExamTabs>
        <Card>
          <DataTable caption="Exam incidents" columns={columns} rows={q.data} rowKey={(i) => i.id!} loading={q.isPending}
            error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
            empty={{ title: "No incidents", description: "Outages of the exam service that affect your candidates appear here automatically." }} />
        </Card>
      </ExamTabs>
      {reporting && <ReportDialog onClose={() => setReporting(false)} />}
    </>
  );
}

function ReportDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [start, setStart] = useState(() => toLocalInput(new Date(Date.now() - 30 * 60_000)));
  const [end, setEnd] = useState(() => toLocalInput(new Date(Date.now() - 60_000)));
  const [description, setDescription] = useState("");
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/incidents", { body: { startedAt: fromLocalInput(start), endedAt: fromLocalInput(end), description } })),
    onSuccess: (i) => { void qc.invalidateQueries({ queryKey: ["exam-incidents"] }); onClose(); void navigate(`/exams/incidents/${i.id}`); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="Report an outage" description="Affected candidates are found from check-ins and presence during the window."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!description.trim()} onClick={() => save.mutate()}>Report</Button></>}>
      <div className="space-y-4">
        {save.isError && <p role="alert" className="text-sm text-danger">{errorInfo(save.error).message}</p>}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Started">{(p) => <Input {...p} type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />}</Field>
          <Field label="Ended">{(p) => <Input {...p} type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} />}</Field>
        </div>
        <Field label="What happened">{(p) => <TextArea {...p} rows={3} maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

export const actionLabel = (a?: string, m?: number | null) => (a === "EXTEND_SESSION" ? `Extend by ${m} min + new check-in` : a === "NEW_CHECK_IN" ? "New check-in" : humanize(a));

export default ExamIncidentsPage;
