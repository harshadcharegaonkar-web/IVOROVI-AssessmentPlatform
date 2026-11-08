import { unwrap, type CandidateAccount } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, Modal, PageHeader, TextArea, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, formatRelative } from "../../lib/format";
import { StatusBadge } from "../integrations/IntegrationShared";
import { ExamTabs } from "./ExamShared";

/** Candidate accounts (separate realm, linked per organization). Never staff or student accounts. */
export function ExamCandidatesPage() {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [resetting, setResetting] = useState<CandidateAccount | null>(null);
  const q = useQuery({ queryKey: ["exam-candidates"], queryFn: async () => unwrap(await api.GET("/api/v1/exam/candidates")) });
  const status = useMutation({
    mutationFn: async (c: CandidateAccount) => c.status === "ACTIVE"
      ? unwrap(await api.POST("/api/v1/exam/candidates/{id}/suspend", { params: { path: { id: c.id! } } }))
      : unwrap(await api.POST("/api/v1/exam/candidates/{id}/reactivate", { params: { path: { id: c.id! } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["exam-candidates"] }),
  });
  const columns: Column<CandidateAccount>[] = [
    { key: "name", header: "Candidate", cell: (c) => <span><span className="font-medium">{c.displayName ?? "—"}</span><span className="block text-xs text-muted">{c.email}</span></span> },
    { key: "portal", header: "Portal", cell: (c) => c.portalName, hideOnMobile: true },
    { key: "status", header: "Status", cell: (c) => <StatusBadge status={c.status} /> },
    { key: "created", header: "Registered", cell: (c) => formatDateTime(c.createdAt), hideOnMobile: true },
    { key: "seen", header: "Last seen", cell: (c) => (c.lastSeenAt ? formatRelative(c.lastSeenAt) : "—"), hideOnMobile: true },
    { key: "actions", header: "Actions", cell: (c) => (
      <div className="flex justify-end gap-1">
        <Button size="sm" variant="ghost" onClick={() => setResetting(c)}>Reset sign-in factor</Button>
        <Button size="sm" variant="ghost" onClick={() => status.mutate(c)}>{c.status === "ACTIVE" ? "Suspend" : "Reactivate"}</Button>
      </div>) },
  ];
  return (
    <>
      <PageHeader title="Examinations" description="Candidates register on a portal with a verified email address. Their accounts are separate from staff and student accounts." />
      <ExamTabs>
        <Card>
          <DataTable caption="Candidate accounts" columns={columns} rows={q.data} rowKey={(c) => c.id!} loading={q.isPending}
            error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
            empty={{ title: "No candidates yet", description: "Candidates appear after they first sign in to a portal." }} />
        </Card>
      </ExamTabs>
      {resetting && <ResetDialog candidate={resetting} onClose={() => setResetting(null)} onDone={() => toast.show("success", "Second factor reset", "The candidate was signed out and emailed.")} />}
    </>
  );
}

function ResetDialog({ candidate, onClose, onDone }: { candidate: CandidateAccount; onClose: () => void; onDone: () => void }) {
  const api = useApi();
  const [reason, setReason] = useState("");
  const reset = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/candidates/{id}/mfa-reset", { params: { path: { id: candidate.id! } }, body: { reason } })),
    onSuccess: () => { onDone(); onClose(); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Reset sign-in factor of ${candidate.displayName ?? candidate.email}`}
      description="Use only after verifying the candidate's identity (lost phone and recovery codes). Removes the authenticator app and recovery codes, signs out every session and emails the candidate."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="danger" loading={reset.isPending} disabled={!reason.trim()} onClick={() => reset.mutate()}>Reset</Button></>}>
      {reset.isError && <p role="alert" className="mb-3 text-sm text-danger">{errorInfo(reset.error).message}</p>}
      <Field label="Reason (recorded in the audit trail)">{(p) => <TextArea {...p} rows={3} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
    </Modal>
  );
}

export default ExamCandidatesPage;
