import { unwrap, type ApiClientCreated } from "@ivorovi/api-client";
import { Badge, Button, Card, CardBody, CardHeader, ConfirmDialog, DataTable, ErrorState, Field, Modal, PageHeader, Select, Skeleton, TextArea } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, KeyRound } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { MonthlyColumns } from "../insights/Charts";
import { dt, SecretOnce, StatusBadge } from "./IntegrationShared";

/** One API client: scopes and restrictions, keys (rotation with grace period, revocation), daily usage. */
export function ApiClientDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const { access } = useSession();
  const manage = access.has("integration.manage");
  const [rotating, setRotating] = useState(false);
  const [grace, setGrace] = useState("24");
  const [revoking, setRevoking] = useState(false);
  const [reason, setReason] = useState("");
  const [issued, setIssued] = useState<ApiClientCreated | null>(null);
  const q = useQuery({ queryKey: ["api-clients", id], queryFn: async () => unwrap(await api.GET("/api/v1/api-clients/{id}", { params: { path: { id } } })) });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["api-clients"] });
  const rotate = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/api-clients/{id}/rotate", { params: { path: { id } }, body: { graceHours: Number(grace) } })),
    onSuccess: (r) => { setIssued(r); setRotating(false); refresh(); } });
  const revokeKey = useMutation({ mutationFn: async (keyId: string) => unwrap(await api.POST("/api/v1/api-clients/{id}/keys/{keyId}/revoke", { params: { path: { id, keyId } } })), onSuccess: refresh });
  const revoke = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/api-clients/{id}/revoke", { params: { path: { id } }, body: { reason } })),
    onSuccess: () => { setRevoking(false); refresh(); } });
  if (q.isError) return <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>;
  if (q.isPending) return <Skeleton className="h-64 w-full" />;
  const c = q.data.client!;
  const active = c.status === "ACTIVE";
  // daily usage folded into the shared column chart (last 30 days, one column per day)
  const usage = (q.data.usage ?? []).map((u) => ({ period: u.day!.slice(0, 7), count: u.requests }));
  return (
    <>
      <PageHeader title={c.name!} description={c.description ?? "API client"}
        actions={manage && active ? <>
          <Button variant="secondary" onClick={() => setRotating(true)}><KeyRound aria-hidden className="h-4 w-4" /> Rotate key</Button>
          <Button variant="danger" onClick={() => setRevoking(true)}><Ban aria-hidden className="h-4 w-4" /> Revoke client</Button>
        </> : undefined} />
      {issued && <div className="mb-4"><SecretOnce label="New API key" value={issued.apiKey!}><Button size="sm" variant="ghost" className="mt-2" onClick={() => setIssued(null)}>I have stored it</Button></SecretOnce></div>}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <Card>
          <CardHeader title="Access" />
          <CardBody>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
              <dt className="text-muted">Status</dt><dd><StatusBadge status={c.status} /></dd>
              <dt className="text-muted">Scopes</dt><dd className="flex flex-wrap gap-1">{c.scopes?.map((s) => <Badge key={s} tone="info">{s}</Badge>)}</dd>
              <dt className="text-muted">Restricted to</dt><dd>{c.orgUnitName ?? "Whole organization"}</dd>
              <dt className="text-muted">Allowed addresses</dt><dd className="break-all">{c.allowedIps?.length ? c.allowedIps.join(", ") : "Any"}</dd>
              <dt className="text-muted">Expires</dt><dd>{dt(c.expiresAt)}</dd>
              <dt className="text-muted">Last used</dt><dd>{dt(c.lastUsedAt)}</dd>
              <dt className="text-muted">Requests (30 days)</dt><dd>{formatNumber(c.requests30Days)} ({formatNumber(c.denied30Days)} denied)</dd>
              <dt className="text-muted">Registered</dt><dd>{dt(c.createdAt)} by {c.createdBy ?? "—"}</dd>
            </dl>
          </CardBody>
        </Card>
        <Card className="xl:col-span-2">
          <CardHeader title="Keys" description="Only the key id and the last four characters are kept in readable form." />
          <DataTable caption="Keys" rows={q.data.keys} rowKey={(k) => k.id!} columns={[
            { key: "id", header: "Key id", cell: (k) => <code className="text-xs">{k.keyId}</code> },
            { key: "hint", header: "Ends with", cell: (k) => <code className="text-xs">…{k.hint}</code> },
            { key: "status", header: "Status", cell: (k) => <StatusBadge status={k.status} /> },
            { key: "created", header: "Created", cell: (k) => dt(k.createdAt), hideOnMobile: true },
            { key: "exp", header: "Valid until", cell: (k) => dt(k.expiresAt), hideOnMobile: true },
            { key: "used", header: "Last used", cell: (k) => dt(k.lastUsedAt), hideOnMobile: true },
            { key: "act", header: "", cell: (k) => manage && active && (k.status === "ACTIVE" || k.status === "EXPIRING")
              ? <Button size="sm" variant="ghost" onClick={() => revokeKey.mutate(k.id!)}>Revoke</Button> : null },
          ]} empty={{ title: "No keys" }} />
        </Card>
      </div>
      <div className="mt-6"><MonthlyColumns title="Requests" description="Requests in the last 30 days, by month" points={mergeMonths(usage)} /></div>
      <Modal open={rotating} onOpenChange={setRotating} title="Rotate key"
        footer={<><Button variant="secondary" onClick={() => setRotating(false)}>Cancel</Button><Button variant="primary" loading={rotate.isPending} onClick={() => rotate.mutate()}>Issue new key</Button></>}>
        <p className="mb-3 text-sm text-muted">A new key is issued. Current keys keep working for the grace period so the other system can switch over.</p>
        <Field label="Grace period">{(p) => <Select {...p} value={grace} onChange={(e) => setGrace(e.target.value)}>
          <option value="0">None (current keys stop now)</option><option value="1">1 hour</option><option value="24">24 hours</option><option value="72">3 days</option><option value="168">7 days</option>
        </Select>}</Field>
        {rotate.isError && <p role="alert" className="mt-2 text-sm text-danger">{errorInfo(rotate.error).message}</p>}
      </Modal>
      <ConfirmDialog open={revoking} onOpenChange={setRevoking} title="Revoke API client?" tone="danger" confirmLabel="Revoke" busy={revoke.isPending}
        description="Every key stops working immediately. This cannot be undone." onConfirm={() => revoke.mutate()}>
        <Field label="Reason (recorded in the audit trail)">{(p) => <TextArea {...p} value={reason} rows={2} maxLength={300} onChange={(e) => setReason(e.target.value)} />}</Field>
        {revoke.isError && <p role="alert" className="mt-2 text-sm text-danger">{errorInfo(revoke.error).message}</p>}
      </ConfirmDialog>
    </>
  );
}

function mergeMonths(points: { period: string; count?: number }[]) {
  const m = new Map<string, number>();
  for (const p of points) m.set(p.period, (m.get(p.period) ?? 0) + (p.count ?? 0));
  return [...m.entries()].map(([period, count]) => ({ period, count }));
}

export default ApiClientDetailPage;
