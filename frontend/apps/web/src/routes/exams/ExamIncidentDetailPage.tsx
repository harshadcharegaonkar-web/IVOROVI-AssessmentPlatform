import { unwrap, type ExamCompensation, type ExamIncidentCandidate } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, CardHeader, DataTable, ErrorState, Field, Input, Link, PageHeader, Select, Skeleton, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { StatusBadge } from "../integrations/IntegrationShared";
import { actionLabel } from "./ExamIncidentsPage";

export function ExamIncidentDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const { access } = useSession();
  const q = useQuery({ queryKey: ["exam-incidents", id], queryFn: async () => unwrap(await api.GET("/api/v1/exam/incidents/{id}", { params: { path: { id } } })) });
  const lost = q.data?.endedAt && q.data.startedAt ? Math.ceil((Date.parse(q.data.endedAt) - Date.parse(q.data.startedAt)) / 60_000) : 0;
  const [action, setAction] = useState("EXTEND_SESSION");
  const [minutes, setMinutes] = useState("");
  const [rationale, setRationale] = useState("");
  const propose = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/incidents/{id}/compensations", { params: { path: { id } },
      body: { action: action as "NO_ACTION", ...(action === "EXTEND_SESSION" ? { extensionMinutes: Number(minutes || lost + 5) } : {}), rationale, version: q.data?.version ?? 0 } })),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["exam-incidents"] }); void qc.invalidateQueries({ queryKey: ["approvals"] }); setRationale(""); },
  });
  if (q.isError) return <ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />;
  if (q.isPending) return <Skeleton className="h-64 w-full" />;
  const i = q.data;
  const candCols: Column<ExamIncidentCandidate>[] = [
    { key: "name", header: "Candidate", cell: (c) => <span><span className="font-medium">{c.displayName ?? "—"}</span><span className="block text-xs text-muted">{c.email}</span></span> },
    { key: "session", header: "Session", cell: (c) => <Link to={`/exams/sessions/${c.sessionId}`} className="hover:underline">{c.sessionName}</Link> },
  ];
  const compCols: Column<ExamCompensation>[] = [
    { key: "action", header: "Compensation", cell: (c) => actionLabel(c.action, c.extensionMinutes) },
    { key: "status", header: "Status", cell: (c) => <StatusBadge status={c.status} /> },
    { key: "by", header: "Proposed by", cell: (c) => `${c.proposedByName ?? "—"}, ${formatDateTime(c.proposedAt)}`, hideOnMobile: true },
    { key: "applied", header: "Applied to", cell: (c) => (c.status === "APPROVED" ? `${c.applied} candidate(s)` : "—") },
    { key: "approval", header: "Approval", cell: (c) => (c.approvalId ? <Link to={`/approvals/${c.approvalId}`} className="text-sm hover:underline">Approval</Link> : null) },
  ];
  const canPropose = access.has("exam.incident.manage") && i.status === "OPEN" && !!i.endedAt;
  return (
    <>
      <PageHeader title={i.description ?? "Incident"} description={`${i.source === "PLATFORM" ? "Detected by the platform" : "Reported by the institution"} · ${formatDateTime(i.startedAt)} – ${i.endedAt ? formatDateTime(i.endedAt) : "ongoing"}`}
        actions={<StatusBadge status={i.status} />} />
      <div className="space-y-6">
        {!i.endedAt && <Alert tone="warning" title="Ongoing">The exam service is still interrupted. Compensation can be proposed once it is back.</Alert>}
        {canPropose && (
          <Card>
            <CardHeader title="Propose compensation" description={`About ${lost} minute(s) were lost. A second person with “Approve candidate compensation” must approve before anything changes for candidates.`} />
            <CardBody className="space-y-4">
              {propose.isError && <p role="alert" className="text-sm text-danger">{errorInfo(propose.error).message}</p>}
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="Action">{(p) => <Select {...p} value={action} onChange={(e) => setAction(e.target.value)}>
                  <option value="EXTEND_SESSION">Extend the session (and a new check-in)</option>
                  <option value="NEW_CHECK_IN">New check-in only</option>
                  <option value="NO_ACTION">No compensation (close)</option></Select>}</Field>
                {action === "EXTEND_SESSION" && <Field label="Extra minutes" hint={`Suggested: lost time + 5 = ${lost + 5} (institution policy decides, Q-06).`}>{(p) =>
                  <Input {...p} type="number" min={1} max={240} placeholder={String(lost + 5)} value={minutes} onChange={(e) => setMinutes(e.target.value)} />}</Field>}
              </div>
              <Field label="Rationale">{(p) => <TextArea {...p} rows={2} maxLength={500} value={rationale} onChange={(e) => setRationale(e.target.value)} />}</Field>
              <Button variant="primary" loading={propose.isPending} disabled={!rationale.trim()} onClick={() => propose.mutate()}>Submit for approval</Button>
            </CardBody>
          </Card>
        )}
        <Card>
          <CardHeader title="Compensation" />
          <DataTable caption="Compensation proposals" columns={compCols} rows={i.compensations} rowKey={(c) => c.id!} empty={{ title: "None proposed", description: "Proposals and their approval status appear here." }} />
        </Card>
        <Card>
          <CardHeader title={`Affected candidates (${i.affected})`} description="Present or checked in while the outage lasted." />
          <DataTable caption="Affected candidates" columns={candCols} rows={i.candidates} rowKey={(c) => `${c.candidateId}-${c.sessionId}`} empty={{ title: "No affected candidates", description: "Nobody was checked in or present in that window." }} />
        </Card>
      </div>
    </>
  );
}

export default ExamIncidentDetailPage;
