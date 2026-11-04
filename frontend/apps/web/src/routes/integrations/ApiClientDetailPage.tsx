import { unwrap, type ApiClientCreated } from "@ivorovi/api-client";
import { Alert, Badge, Button, Link, Card, CardBody, CardHeader, ConfirmDialog, DataTable, ErrorState, Field, Modal, PageHeader, Select, Skeleton, TextArea } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, KeyRound, Pause, Play, UserRoundCog } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { MonthlyColumns } from "../insights/Charts";
import { dt, SecretOnce, StatusBadge } from "./IntegrationShared";
import { UserSelect } from "./ServiceIdentitiesPage";

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
  const [reassigning, setReassigning] = useState(false);
  const [newOwner, setNewOwner] = useState("");
  const [suspending, setSuspending] = useState(false);
  const q = useQuery({ queryKey: ["api-clients", id], queryFn: async () => unwrap(await api.GET("/api/v1/api-clients/{id}", { params: { path: { id } } })) });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["api-clients"] });
  const rotate = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/api-clients/{id}/rotate", { params: { path: { id } }, body: { graceHours: Number(grace) } })),
    onSuccess: (r) => { setIssued(r); setRotating(false); refresh(); } });
  const revokeKey = useMutation({ mutationFn: async (keyId: string) => unwrap(await api.POST("/api/v1/api-clients/{id}/keys/{keyId}/revoke", { params: { path: { id, keyId } } })), onSuccess: refresh });
  const revoke = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/api-clients/{id}/revoke", { params: { path: { id } }, body: { reason } })),
    onSuccess: () => { setRevoking(false); refresh(); } });
  const reassign = useMutation({ mutationFn: async () => unwrap(await api.PUT("/api/v1/api-clients/{id}/owner", { params: { path: { id } },
    body: { userId: newOwner, version: q.data?.client?.version } })), onSuccess: () => { setReassigning(false); refresh(); } });
  const suspendC = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/api-clients/{id}/suspend", { params: { path: { id } }, body: { reason } })),
    onSuccess: () => { setSuspending(false); setReason(""); refresh(); } });
  const resumeC = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/api-clients/{id}/resume", { params: { path: { id } } })), onSuccess: refresh });
  if (q.isError) return <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>;
  if (q.isPending) return <Skeleton className="h-64 w-full" />;
  const c = q.data.client!;
  const active = c.status === "ACTIVE";
  const suspended = c.status === "SUSPENDED";
  const lost = Object.entries(q.data.lostScopes ?? {});
  // daily usage folded into the shared column chart (last 30 days, one column per day)
  const usage = (q.data.usage ?? []).map((u) => ({ period: u.day!.slice(0, 7), count: u.requests }));
  return (
    <>
      <PageHeader title={c.name!} description={c.description ?? "API client"}
        actions={manage && (active || suspended) ? <>
          {active && <Button variant="secondary" onClick={() => setRotating(true)}><KeyRound aria-hidden className="h-4 w-4" /> Rotate key</Button>}
          {c.kind === "DELEGATED" && <Button variant="secondary" onClick={() => setReassigning(true)}><UserRoundCog aria-hidden className="h-4 w-4" /> Reassign owner</Button>}
          {active && <Button variant="secondary" onClick={() => setSuspending(true)}><Pause aria-hidden className="h-4 w-4" /> Suspend</Button>}
          {suspended && <Button variant="primary" loading={resumeC.isPending} onClick={() => resumeC.mutate()}><Play aria-hidden className="h-4 w-4" /> Resume</Button>}
          <Button variant="danger" onClick={() => setRevoking(true)}><Ban aria-hidden className="h-4 w-4" /> Revoke client</Button>
        </> : undefined} />
      {suspended && <Alert tone="warning" title="Suspended" className="mb-4">
        {c.suspendedReason === "owner_inactive" ? "Its owner is no longer active. Reassign it to another owner, or revoke it. It is never resumed automatically."
          : "Its keys do not work until an integration manager resumes it."}{resumeC.isError ? ` ${errorInfo(resumeC.error).message}` : ""}</Alert>}
      {lost.length > 0 && !suspended && <Alert tone="warning" title="Some scopes do not work right now" className="mb-4">
        {lost.map(([s, why]) => `${s} (${why === "owner_lacks" ? "the owner no longer holds it" : why === "role_missing" ? "no role grants it" : why === "envelope" ? "outside the approved envelope" : why.replaceAll("_", " ")})`).join(", ")}.
        Requests needing them are refused until the authority behind them is restored.</Alert>}
      {issued && <div className="mb-4"><SecretOnce label="New API key" value={issued.apiKey!}><Button size="sm" variant="ghost" className="mt-2" onClick={() => setIssued(null)}>I have stored it</Button></SecretOnce></div>}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <Card>
          <CardHeader title="Access" />
          <CardBody>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
              <dt className="text-muted">Status</dt><dd data-testid="client-status"><StatusBadge status={c.status} /></dd>
              <dt className="text-muted">Acts for</dt><dd>{c.kind === "SERVICE" ? <Link to={`/integrations/service-identities/${c.serviceIdentityId}`} className="text-primary hover:underline">Service identity: {c.serviceIdentityName}</Link> : `${c.ownerName} (owner)`}</dd>
              <dt className="text-muted">Effective now</dt><dd className="flex flex-wrap gap-1" data-testid="client-effective">{q.data.effectiveScopes?.length ? q.data.effectiveScopes.map((s) => <Badge key={s} tone="success">{s}</Badge>) : "None"}</dd>
              <dt className="text-muted">Registered scopes</dt><dd className="flex flex-wrap gap-1">{c.scopes?.map((s) => <Badge key={s} tone="info">{s}</Badge>)}</dd>
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
      <Modal open={reassigning} onOpenChange={setReassigning} title="Reassign owner"
        footer={<><Button variant="secondary" onClick={() => setReassigning(false)}>Cancel</Button><Button variant="primary" loading={reassign.isPending} disabled={!newOwner} onClick={() => reassign.mutate()}>Reassign</Button></>}>
        <p className="mb-3 text-sm text-muted">The new owner must be active and hold every registered scope over the client's whole scope right now.</p>
        <UserSelect label="New owner" value={newOwner} onChange={setNewOwner} />
        {reassign.isError && <p role="alert" className="mt-2 text-sm text-danger">{errorInfo(reassign.error).message}</p>}
      </Modal>
      <ConfirmDialog open={suspending} onOpenChange={setSuspending} title="Suspend API client?" confirmLabel="Suspend" busy={suspendC.isPending}
        description="Its keys stop working until it is resumed." onConfirm={() => suspendC.mutate()}>
        <Field label="Reason (recorded in the audit trail)">{(p) => <TextArea {...p} value={reason} rows={2} maxLength={300} onChange={(e) => setReason(e.target.value)} />}</Field>
        {suspendC.isError && <p role="alert" className="mt-2 text-sm text-danger">{errorInfo(suspendC.error).message}</p>}
      </ConfirmDialog>
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
