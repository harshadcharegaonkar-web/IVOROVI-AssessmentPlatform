import { unwrap, type WebhookDelivery } from "@ivorovi/api-client";
import { Button, Card, CardBody, CardHeader, ConfirmDialog, DataTable, ErrorState, Field, Modal, PageHeader, Select, Skeleton, useToast, type Column } from "@ivorovi/ui";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Pause, Pencil, Play, Send, Trash2 } from "lucide-react";
import { useState } from "react";
import { useNavigate, useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { dt, SecretOnce, StatusBadge } from "./IntegrationShared";
import { EndpointDialog } from "./WebhooksPage";

/** One webhook endpoint: status, test ping, pause/resume, secret rotation, delivery history with payload and replay. */
export function WebhookDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const { access } = useSession();
  const manage = access.has("integration.manage");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [secret, setSecret] = useState<string | null>(null);
  const [shown, setShown] = useState<WebhookDelivery | null>(null);
  const ep = useQuery({ queryKey: ["webhooks", id], queryFn: async () => unwrap(await api.GET("/api/v1/webhooks/{id}", { params: { path: { id } } })) });
  const deliveries = useQuery({ queryKey: ["webhooks", id, "deliveries", status, page], placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/webhooks/{id}/deliveries", { params: { path: { id }, query: { page, size: 15, ...(status ? { status } : {}) } } })) });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["webhooks"] });
  const test = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/webhooks/{id}/test", { params: { path: { id } } })),
    onSuccess: (r) => { toast.show(r.delivered ? "success" : "danger", r.delivered ? "Test delivered" : "Test failed", r.delivery?.lastError ?? `HTTP ${r.delivery?.lastStatusCode ?? "—"}`); refresh(); } });
  const setState = useMutation({ mutationFn: async (s: "ACTIVE" | "PAUSED") => unwrap(await api.PUT("/api/v1/webhooks/{id}/status", { params: { path: { id } }, body: { status: s } })), onSuccess: refresh });
  const rotate = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/webhooks/{id}/rotate-secret", { params: { path: { id } } })), onSuccess: (r) => setSecret(r.signingSecret ?? null) });
  const replay = useMutation({ mutationFn: async (d: string) => unwrap(await api.POST("/api/v1/webhooks/{id}/deliveries/{deliveryId}/replay", { params: { path: { id, deliveryId: d } } })),
    onSuccess: () => { toast.show("success", "Queued again", "The delivery will be attempted shortly."); refresh(); }, onError: (e) => toast.show("danger", "Could not replay", errorInfo(e).message) });
  const remove = useMutation({ mutationFn: async () => unwrap(await api.DELETE("/api/v1/webhooks/{id}", { params: { path: { id } } })), onSuccess: () => { refresh(); void navigate("/integrations/webhooks"); } });
  if (ep.isError) return <Card><ErrorState message={errorInfo(ep.error).message} onRetry={() => void ep.refetch()} /></Card>;
  if (ep.isPending) return <Skeleton className="h-64 w-full" />;
  const w = ep.data;
  const cols: Column<WebhookDelivery>[] = [
    { key: "created", header: "Created", cell: (d) => dt(d.createdAt) },
    { key: "type", header: "Event", cell: (d) => <code className="text-xs">{d.eventType}</code> },
    { key: "status", header: "Status", cell: (d) => <StatusBadge status={d.status} /> },
    { key: "attempts", header: "Attempts", cell: (d) => formatNumber(d.attempts) },
    { key: "code", header: "Response", cell: (d) => d.lastError ?? (d.lastStatusCode ? `HTTP ${d.lastStatusCode}` : "—"), hideOnMobile: true },
    { key: "next", header: "Next attempt", cell: (d) => (d.status === "PENDING" ? dt(d.nextAttemptAt) : "—"), hideOnMobile: true },
    { key: "act", header: "", cell: (d) => <span className="flex gap-1">
      <Button size="sm" variant="ghost" onClick={() => setShown(d)}>Payload</Button>
      {manage && d.status !== "PENDING" && <Button size="sm" variant="ghost" onClick={() => replay.mutate(d.id!)}>Replay</Button>}
    </span> },
  ];
  return (
    <>
      <PageHeader title={w.name!} description={w.url}
        actions={manage ? <>
          <Button variant="secondary" onClick={() => test.mutate()} loading={test.isPending}><Send aria-hidden className="h-4 w-4" /> Send test</Button>
          {w.status === "ACTIVE" ? <Button variant="secondary" onClick={() => setState.mutate("PAUSED")}><Pause aria-hidden className="h-4 w-4" /> Pause</Button>
            : <Button variant="primary" onClick={() => setState.mutate("ACTIVE")}><Play aria-hidden className="h-4 w-4" /> Resume</Button>}
          <Button variant="secondary" onClick={() => setEditing(true)}><Pencil aria-hidden className="h-4 w-4" /> Edit</Button>
          <Button variant="secondary" onClick={() => rotate.mutate()} loading={rotate.isPending}><KeyRound aria-hidden className="h-4 w-4" /> Rotate secret</Button>
        </> : undefined} />
      {secret && <div className="mb-4"><SecretOnce label="New signing secret" value={secret}><Button size="sm" variant="ghost" className="mt-2" onClick={() => setSecret(null)}>I have stored it</Button></SecretOnce></div>}
      <Card className="mb-6">
        <CardHeader title="Endpoint" />
        <CardBody>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm md:grid-cols-4">
            <div><dt className="text-muted">Status</dt><dd data-testid="webhook-status"><StatusBadge status={w.status} /></dd></div>
            <div><dt className="text-muted">Consecutive failures</dt><dd>{formatNumber(w.consecutiveFailures)}</dd></div>
            <div><dt className="text-muted">Delivered / failed (30 days)</dt><dd>{formatNumber(w.delivered30Days)} / {formatNumber(w.failed30Days)}</dd></div>
            <div><dt className="text-muted">Pending</dt><dd>{formatNumber(w.pending)}</dd></div>
            <div><dt className="text-muted">Last success</dt><dd>{dt(w.lastSuccessAt)}</dd></div>
            <div><dt className="text-muted">Identifiers</dt><dd>{w.includeIdentifiers ? "Included" : "Not included"}</dd></div>
            <div className="col-span-2"><dt className="text-muted">Events</dt><dd>{w.eventTypes?.join(", ")}</dd></div>
          </dl>
          {w.status === "AUTO_DISABLED" && <p className="mt-3 rounded-md bg-danger-soft px-3 py-2 text-sm text-danger">Disabled automatically after repeated failures. Fix the receiver, then resume; failed deliveries can be replayed.</p>}
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Deliveries" actions={<Field label="Status" className="w-40">{(p) => <Select {...p} value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="">All</option><option value="PENDING">Pending</option><option value="DELIVERED">Delivered</option><option value="FAILED">Failed</option></Select>}</Field>} />
        <DataTable caption="Deliveries" columns={cols} rows={deliveries.data?.items} rowKey={(d) => d.id!} loading={deliveries.isPending}
          error={deliveries.isError ? errorInfo(deliveries.error) : null} onRetry={() => void deliveries.refetch()}
          page={deliveries.data ? { page, totalPages: deliveries.data.totalPages ?? 0, totalElements: deliveries.data.totalElements ?? 0, onPageChange: setPage } : undefined}
          empty={{ title: "No deliveries yet", description: "Send a test or wait for a subscribed event." }} />
      </Card>
      {manage && <div className="mt-6"><Button variant="ghost" onClick={() => setDeleting(true)}><Trash2 aria-hidden className="h-4 w-4" /> Delete endpoint</Button></div>}
      {editing && <EndpointDialog initial={w} onClose={() => { setEditing(false); refresh(); }} />}
      <Modal open={!!shown} onOpenChange={(o) => !o && setShown(null)} title="Delivery payload" size="lg" footer={<Button variant="secondary" onClick={() => setShown(null)}>Close</Button>}>
        <pre className="max-h-96 overflow-auto rounded-md bg-surface-muted p-3 text-xs">{shown?.payload ? JSON.stringify(JSON.parse(shown.payload), null, 2) : ""}</pre>
      </Modal>
      <ConfirmDialog open={deleting} onOpenChange={setDeleting} title="Delete endpoint?" tone="danger" confirmLabel="Delete" busy={remove.isPending}
        description="The endpoint and its delivery history are removed." onConfirm={() => remove.mutate()} />
    </>
  );
}

export default WebhookDetailPage;
