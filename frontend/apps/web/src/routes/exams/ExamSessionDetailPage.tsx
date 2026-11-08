import { unwrap, type IssuedSessionCode, type SessionCheckIn, type SessionCode } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, CardHeader, ConfirmDialog, DataTable, ErrorState, Field, Input, PageHeader, Skeleton, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, formatNumber, formatRelative } from "../../lib/format";
import { StatusBadge } from "../integrations/IntegrationShared";

export function ExamSessionDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const [count, setCount] = useState("10");
  const [issued, setIssued] = useState<IssuedSessionCode[] | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const s = useQuery({ queryKey: ["exam-sessions", id], queryFn: async () => unwrap(await api.GET("/api/v1/exam/sessions/{id}", { params: { path: { id } } })) });
  const codes = useQuery({ queryKey: ["exam-sessions", id, "codes"], queryFn: async () => unwrap(await api.GET("/api/v1/exam/sessions/{id}/codes", { params: { path: { id } } })) });
  const checkIns = useQuery({ queryKey: ["exam-sessions", id, "check-ins"], refetchInterval: 15_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/exam/sessions/{id}/check-ins", { params: { path: { id } } })) });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["exam-sessions"] });
  const generate = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/sessions/{id}/codes", { params: { path: { id } }, body: { count: Number(count) } })),
    onSuccess: (c) => { setIssued(c); refresh(); },
  });
  const revoke = useMutation({
    mutationFn: async (codeId: string) => unwrap(await api.POST("/api/v1/exam/sessions/{id}/codes/{codeId}/revoke", { params: { path: { id, codeId } } })),
    onSuccess: refresh,
  });
  const cancel = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/exam/sessions/{id}/cancel", { params: { path: { id } } })),
    onSuccess: () => { setCancelling(false); refresh(); } });
  if (s.isError) return <ErrorState message={errorInfo(s.error).message} onRetry={() => void s.refetch()} />;
  if (s.isPending) return <Skeleton className="h-64 w-full" />;
  const session = s.data;
  const scheduled = session.status === "SCHEDULED";
  const codeCols: Column<SessionCode>[] = [
    { key: "hint", header: "Code", cell: (c) => <code className="text-xs">•••••-•••{c.hint}</code> },
    { key: "status", header: "Status", cell: (c) => <StatusBadge status={c.status} /> },
    { key: "valid", header: "Valid", cell: (c) => <span className="text-sm">{formatDateTime(c.validFrom)} – {formatDateTime(c.validUntil)}</span>, hideOnMobile: true },
    { key: "used", header: "Used by", cell: (c) => (c.consumedByName ? `${c.consumedByName}, ${formatRelative(c.consumedAt)}` : c.issuedForName ? `Bound to ${c.issuedForName}` : "—") },
    { key: "actions", header: "Actions", cell: (c) => c.status === "AVAILABLE" || c.status === "NOT_YET_VALID"
      ? <Button size="sm" variant="ghost" onClick={() => revoke.mutate(c.id!)}>Revoke</Button> : null },
  ];
  const ciCols: Column<SessionCheckIn>[] = [
    { key: "name", header: "Candidate", cell: (c) => <span><span className="font-medium">{c.displayName ?? "—"}</span><span className="block text-xs text-muted">{c.email}</span></span> },
    { key: "at", header: "Checked in", cell: (c) => formatDateTime(c.checkedInAt) },
    { key: "clock", header: "Clock offset", cell: (c) => (c.clockOffsetMs == null ? "—" : `${(c.clockOffsetMs / 1000).toFixed(1)} s`), hideOnMobile: true },
    { key: "seen", header: "Last contact", cell: (c) => (c.lastSeenAt ? formatRelative(c.lastSeenAt) : "—") },
    { key: "beats", header: "Heartbeats", cell: (c) => formatNumber(c.beats ?? 0), hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title={session.name ?? "Session"} description={`${session.portalName} · ${formatDateTime(session.startsAt)} – ${formatDateTime(session.endsAt)}`}
        actions={scheduled ? <Button variant="secondary" onClick={() => setCancelling(true)}>Cancel session</Button> : <StatusBadge status={session.status} />} />
      <div className="space-y-6">
        {scheduled && (
          <Card>
            <CardHeader title="Generate session codes" description="Single-use codes, valid from 30 minutes before the start until the end. Give one to each candidate (or release them at the start of a supervised session)." />
            <CardBody className="space-y-4">
              {generate.isError && <p role="alert" className="text-sm text-danger">{errorInfo(generate.error).message}</p>}
              <div className="flex flex-wrap items-end gap-3">
                <Field label="Number of codes">{(p) => <Input {...p} type="number" min={1} max={500} className="w-32" value={count} onChange={(e) => setCount(e.target.value)} />}</Field>
                <Button variant="primary" loading={generate.isPending} disabled={!(Number(count) >= 1 && Number(count) <= 500)} onClick={() => generate.mutate()}>Generate</Button>
              </div>
              {issued && (
                <Alert tone="warning" title={`${issued.length} codes — shown only once`}>
                  <p>Copy or print them now. They are stored only as keyed hashes and cannot be shown again.</p>
                  <ul data-testid="issued-codes" className="mt-2 grid grid-cols-2 gap-1 font-mono text-sm sm:grid-cols-4">{issued.map((c) => <li key={c.id}>{c.code}</li>)}</ul>
                  <Button className="mt-2" size="sm" variant="secondary" onClick={() => void navigator.clipboard?.writeText(issued.map((c) => c.code).join("\n"))}>Copy all</Button>
                </Alert>
              )}
            </CardBody>
          </Card>
        )}
        <Card>
          <CardHeader title="Checked-in candidates" description="Presence is reported by the exam app every 15 seconds." />
          <DataTable caption="Checked-in candidates" columns={ciCols} rows={checkIns.data} rowKey={(c) => c.candidateId!} loading={checkIns.isPending}
            error={checkIns.isError ? errorInfo(checkIns.error) : null} onRetry={() => void checkIns.refetch()} empty={{ title: "No check-ins yet", description: "Candidates appear here after entering a code." }} />
        </Card>
        <Card>
          <CardHeader title="Codes" />
          <DataTable caption="Session codes" columns={codeCols} rows={codes.data} rowKey={(c) => c.id!} loading={codes.isPending}
            error={codes.isError ? errorInfo(codes.error) : null} onRetry={() => void codes.refetch()} empty={{ title: "No codes", description: "Generate codes above." }} />
        </Card>
      </div>
      <ConfirmDialog open={cancelling} onOpenChange={setCancelling} title="Cancel this session?" confirmLabel="Cancel session" tone="danger"
        description="Unused codes are revoked immediately. Existing check-ins stay on record." busy={cancel.isPending} onConfirm={() => cancel.mutate()} />
    </>
  );
}

export default ExamSessionDetailPage;
