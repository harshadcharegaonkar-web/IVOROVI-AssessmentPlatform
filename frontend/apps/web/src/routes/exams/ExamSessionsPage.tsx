import { unwrap, type ReadinessSession } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, Input, Link, Modal, PageHeader, Select, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, formatNumber } from "../../lib/format";
import { StatusBadge } from "../integrations/IntegrationShared";
import { ExamTabs, fromLocalInput, toLocalInput } from "./ExamShared";

/** Readiness check-in sessions: the 2.0 stand-in for exam sessions (codes, check-in, presence, compensation). */
export function ExamSessionsPage() {
  const api = useApi();
  const [creating, setCreating] = useState(false);
  const q = useQuery({ queryKey: ["exam-sessions"], queryFn: async () => unwrap(await api.GET("/api/v1/exam/sessions")) });
  const columns: Column<ReadinessSession>[] = [
    { key: "name", header: "Session", cell: (s) => <Link to={`/exams/sessions/${s.id}`} className="font-medium text-fg hover:underline">{s.name}</Link> },
    { key: "portal", header: "Portal", cell: (s) => s.portalName, hideOnMobile: true },
    { key: "window", header: "Window", cell: (s) => <span className="text-sm">{formatDateTime(s.startsAt)} – {formatDateTime(s.endsAt)}</span> },
    { key: "status", header: "Status", cell: (s) => <StatusBadge status={s.status} /> },
    { key: "codes", header: "Codes used", cell: (s) => `${formatNumber(s.codesUsed)} / ${formatNumber(s.codes)}`, hideOnMobile: true },
    { key: "checkins", header: "Checked in", cell: (s) => formatNumber(s.checkIns) },
  ];
  return (
    <>
      <PageHeader title="Examinations" description="Readiness sessions let candidates check in with a single-use code and verify their device before real exams exist."
        actions={<Button variant="primary" onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New session</Button>} />
      <ExamTabs>
        <Card>
          <DataTable caption="Readiness sessions" columns={columns} rows={q.data} rowKey={(s) => s.id!} loading={q.isPending}
            error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
            empty={{ title: "No sessions", description: "Create a readiness session on one of your portals." }} />
        </Card>
      </ExamTabs>
      {creating && <CreateSession onClose={() => setCreating(false)} />}
    </>
  );
}

function CreateSession({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const portals = useQuery({ queryKey: ["exam-portals"], queryFn: async () => unwrap(await api.GET("/api/v1/exam/portals")) });
  const [portal, setPortal] = useState("");
  const [name, setName] = useState("Readiness check");
  const [start, setStart] = useState(() => toLocalInput(new Date(Date.now() + 15 * 60_000)));
  const [end, setEnd] = useState(() => toLocalInput(new Date(Date.now() + 135 * 60_000)));
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/sessions", { body: { portalId: portal, name, startsAt: fromLocalInput(start), endsAt: fromLocalInput(end) } })),
    onSuccess: (s) => { void qc.invalidateQueries({ queryKey: ["exam-sessions"] }); onClose(); void navigate(`/exams/sessions/${s.id}`); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="New readiness session"
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!portal || !name.trim()} onClick={() => save.mutate()}>Create</Button></>}>
      <div className="space-y-4">
        {save.isError && <p role="alert" className="text-sm text-danger">{errorInfo(save.error).message}</p>}
        <Field label="Portal">{(p) => <Select {...p} value={portal} onChange={(e) => setPortal(e.target.value)}>
          <option value="">Choose a portal…</option>{portals.data?.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</Select>}</Field>
        <Field label="Name">{(p) => <Input {...p} value={name} maxLength={150} onChange={(e) => setName(e.target.value)} />}</Field>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Starts">{(p) => <Input {...p} type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />}</Field>
          <Field label="Ends">{(p) => <Input {...p} type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} />}</Field>
        </div>
      </div>
    </Modal>
  );
}

export default ExamSessionsPage;
