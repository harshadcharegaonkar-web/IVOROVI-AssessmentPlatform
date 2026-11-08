import { unwrap } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, CardHeader, ErrorState, Field, PageHeader, Skeleton, TextArea } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, humanize } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { StatusBadge } from "../integrations/IntegrationShared";

export function ApprovalDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const { me } = useSession();
  const [reason, setReason] = useState("");
  const q = useQuery({ queryKey: ["approvals", "one", id], queryFn: async () => unwrap(await api.GET("/api/v1/approvals/{id}", { params: { path: { id } } })) });
  const done = () => void qc.invalidateQueries({ queryKey: ["approvals"] });
  const decide = useMutation({
    mutationFn: async (approve: boolean) => unwrap(await api.POST("/api/v1/approvals/{id}/decision", { params: { path: { id } },
      body: { approve, ...(reason.trim() ? { reason: reason.trim() } : {}), version: q.data?.version } })),
    onSuccess: done,
  });
  const cancel = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/approvals/{id}/cancel", { params: { path: { id } } })), onSuccess: done });
  const escalate = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/approvals/{id}/escalate", { params: { path: { id } } })), onSuccess: done });
  if (q.isError) return <ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />;
  if (q.isPending) return <Skeleton className="h-64 w-full" />;
  const r = q.data;
  const mine = r.requestedBy === me?.userId;
  const pending = r.status === "PENDING";
  const err = decide.error ?? cancel.error ?? escalate.error;
  return (
    <>
      <PageHeader title={r.summary ?? "Approval"} description={`${r.typeLabel} · requested by ${r.requestedByName} on ${formatDateTime(r.requestedAt)}`} />
      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title="What is requested" />
          <CardBody>
            <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
              {Object.entries(r.payload ?? {}).map(([k, v]) => (
                <div key={k}><dt className="text-muted">{humanize(k.replace(/([A-Z])/g, "_$1"))}</dt><dd className="font-medium">{String(v)}</dd></div>
              ))}
              <div><dt className="text-muted">Required permission</dt><dd className="font-mono text-xs">{r.requiredPermission}</dd></div>
              <div><dt className="text-muted">Unit</dt><dd>{r.orgUnitName ?? "Whole organization"}</dd></div>
            </dl>
          </CardBody>
        </Card>
        <Card>
          <CardHeader title="Decision" />
          <CardBody className="space-y-4">
            <p className="flex items-center gap-2 text-sm">Status <StatusBadge status={r.status} /></p>
            {r.decidedByName && <p className="text-sm">Decided by {r.decidedByName} on {formatDateTime(r.decidedAt)}{r.decisionReason ? `: “${r.decisionReason}”` : ""}</p>}
            {pending && <p className="text-sm text-muted">Expires {formatDateTime(r.expiresAt)}. Eligible approvers other than the requester: {r.eligibleApprovers}.</p>}
            {err && <p role="alert" className="text-sm text-danger">{errorInfo(err).message}</p>}
            {pending && mine && (
              <Alert tone="info" title="You requested this">Someone else with the required permission must decide it. {r.eligibleApprovers === 0 ? "Nobody else is eligible yet: escalate to organization-level approvers, or the request stays pending." : ""}</Alert>
            )}
            {pending && r.canDecide && (
              <>
                <Field label="Reason" hint="Required when rejecting.">{(p) => <TextArea {...p} rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
                <div className="flex flex-wrap gap-2">
                  <Button variant="primary" loading={decide.isPending && decide.variables === true} onClick={() => decide.mutate(true)}>Approve</Button>
                  <Button variant="danger" loading={decide.isPending && decide.variables === false} disabled={!reason.trim()} onClick={() => decide.mutate(false)}>Reject</Button>
                </div>
              </>
            )}
            {pending && mine && (
              <div className="flex flex-wrap gap-2">
                <Button variant="secondary" loading={escalate.isPending} onClick={() => escalate.mutate()} disabled={!!r.escalatedAt}>{r.escalatedAt ? "Escalated" : "Escalate"}</Button>
                <Button variant="ghost" loading={cancel.isPending} onClick={() => cancel.mutate()}>Cancel request</Button>
              </div>
            )}
          </CardBody>
        </Card>
      </div>
    </>
  );
}

export default ApprovalDetailPage;
