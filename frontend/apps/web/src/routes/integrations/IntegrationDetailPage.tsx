import { unwrap, type IntegrationAdapterInfo, type IntegrationRun } from "@ivorovi/api-client";
import { Button, Card, CardBody, CardHeader, ConfirmDialog, DataTable, ErrorState, Field, Input, PageHeader, Skeleton, useToast, type Column } from "@ivorovi/ui";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PlugZap, Power, RefreshCw, RotateCcw, Trash2 } from "lucide-react";
import { useState } from "react";
import { useNavigate, useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber, humanize } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { dt, StatusBadge } from "./IntegrationShared";

/** One integration: status, configuration (secret write-only), connection test, sync, enable/disable, run history, retry. */
export function IntegrationDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const { access } = useSession();
  const manage = access.has("integration.manage");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const q = useQuery({ queryKey: ["integrations", id], queryFn: async () => unwrap(await api.GET("/api/v1/integrations/{id}", { params: { path: { id } } })) });
  const runs = useQuery({ queryKey: ["integrations", id, "runs", page], placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/v1/integrations/{id}/runs", { params: { path: { id }, query: { page, size: 10 } } })) });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["integrations"] });
  const outcome = (r: IntegrationRun) => toast.show(r.status === "SUCCEEDED" ? "success" : "danger", r.status === "SUCCEEDED" ? "Succeeded" : "Failed", r.message ?? undefined);
  const test = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/integrations/{id}/test", { params: { path: { id } } })), onSuccess: (r) => { outcome(r); refresh(); },
    onError: (e) => toast.show("danger", "Could not run", errorInfo(e).message) });
  const sync = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/integrations/{id}/sync", { params: { path: { id } } })), onSuccess: (r) => { outcome(r); refresh(); },
    onError: (e) => toast.show("danger", "Could not sync", errorInfo(e).message) });
  const retry = useMutation({ mutationFn: async (runId: string) => unwrap(await api.POST("/api/v1/integrations/{id}/runs/{runId}/retry", { params: { path: { id, runId } } })),
    onSuccess: (r) => { outcome(r); refresh(); }, onError: (e) => toast.show("danger", "Could not retry", errorInfo(e).message) });
  const enable = useMutation({ mutationFn: async (enabled: boolean) => unwrap(await api.PUT("/api/v1/integrations/{id}/enabled", { params: { path: { id } }, body: { enabled } })), onSuccess: refresh });
  const remove = useMutation({ mutationFn: async () => unwrap(await api.DELETE("/api/v1/integrations/{id}", { params: { path: { id } } })),
    onSuccess: () => { refresh(); void navigate("/integrations"); }, onError: (e) => toast.show("danger", "Could not delete", errorInfo(e).message) });

  if (q.isError) return <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>;
  if (q.isPending) return <Skeleton className="h-64 w-full" />;
  const i = q.data.integration!;
  const a = q.data.adapter!;
  const cols: Column<IntegrationRun>[] = [
    { key: "started", header: "Started", cell: (r) => dt(r.startedAt) },
    { key: "op", header: "Operation", cell: (r) => humanize(r.operation) },
    { key: "trigger", header: "Trigger", cell: (r) => humanize(r.trigger), hideOnMobile: true },
    { key: "status", header: "Outcome", cell: (r) => <StatusBadge status={r.status} /> },
    { key: "attempts", header: "Attempts", cell: (r) => formatNumber(r.attempts), hideOnMobile: true },
    { key: "ms", header: "Duration", cell: (r) => (r.durationMs == null ? "—" : `${formatNumber(r.durationMs)} ms`), hideOnMobile: true },
    { key: "msg", header: "Message", cell: (r) => <span className="text-sm text-muted">{r.message}</span> },
    { key: "by", header: "By", cell: (r) => r.startedBy ?? "Scheduled", hideOnMobile: true },
    { key: "act", header: "", cell: (r) => manage && r.status === "FAILED" ? <Button size="sm" variant="ghost" loading={retry.isPending && retry.variables === r.id} onClick={() => retry.mutate(r.id!)}><RotateCcw aria-hidden className="h-4 w-4" /> Retry</Button> : null },
  ];
  return (
    <>
      <PageHeader title={i.name!} description={`${a.label} · ${humanize(i.kind)}${i.description ? ` · ${i.description}` : ""}`}
        actions={manage ? <>
          <Button variant="secondary" onClick={() => test.mutate()} loading={test.isPending}><PlugZap aria-hidden className="h-4 w-4" /> Test connection</Button>
          {a.supportsSync && <Button variant="secondary" onClick={() => sync.mutate()} loading={sync.isPending} disabled={!i.enabled}><RefreshCw aria-hidden className="h-4 w-4" /> Sync now</Button>}
          <Button variant={i.enabled ? "secondary" : "primary"} onClick={() => enable.mutate(!i.enabled)} loading={enable.isPending}><Power aria-hidden className="h-4 w-4" /> {i.enabled ? "Disable" : "Enable"}</Button>
        </> : undefined} />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <Card>
          <CardHeader title="Status" />
          <CardBody>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
              <dt className="text-muted">Connection</dt><dd data-testid="integration-status"><StatusBadge status={i.connectionStatus} /></dd>
              <dt className="text-muted">Enabled</dt><dd>{i.enabled ? "Yes" : "No"}</dd>
              <dt className="text-muted">Last check</dt><dd>{dt(i.lastCheckedAt)}</dd>
              <dt className="text-muted">Last success</dt><dd>{dt(i.lastSuccessAt)}</dd>
              <dt className="text-muted">Last sync</dt><dd>{dt(i.lastSyncAt)}</dd>
              <dt className="text-muted">Consecutive failures</dt><dd>{formatNumber(i.consecutiveFailures)}</dd>
              <dt className="text-muted">Runs (30 days)</dt><dd>{Object.entries(q.data.runsLast30Days ?? {}).map(([k, v]) => `${humanize(k)} ${v}`).join(" · ") || "None"}</dd>
            </dl>
            {i.lastError && <p className="mt-3 rounded-md bg-danger-soft px-3 py-2 text-sm text-danger">{i.lastError}</p>}
            {a.supportsSync && <p className="mt-3 text-xs text-muted">{a.syncDescription}</p>}
          </CardBody>
        </Card>
        <Card className="xl:col-span-2">
          <CardHeader title="Configuration" actions={manage ? <Button variant="secondary" size="sm" onClick={() => setEditing(!editing)}>{editing ? "Cancel" : "Edit"}</Button> : undefined} />
          <CardBody>
            {editing ? <ConfigForm id={id} version={i.version!} adapter={a} config={i.config ?? {}} secretSet={!!i.secretSet} onDone={() => { setEditing(false); refresh(); }} /> : (
              <dl className="grid grid-cols-1 gap-x-4 gap-y-2 text-sm sm:grid-cols-[12rem_1fr]">
                {a.fields?.map((f) => <div key={f.key} className="contents"><dt className="text-muted">{f.label}</dt><dd className="break-all">{i.config?.[f.key!] || "—"}</dd></div>)}
                {a.secretLabel && <><dt className="text-muted">{a.secretLabel}</dt><dd>{i.secretSet ? "Set (hidden)" : "Not set"}</dd></>}
              </dl>
            )}
          </CardBody>
        </Card>
      </div>
      <Card className="mt-6">
        <CardHeader title="Execution history" description="Connection tests and syncs, manual, scheduled and retries" />
        <DataTable caption="Execution history" columns={cols} rows={runs.data?.items} rowKey={(r) => r.id!} loading={runs.isPending}
          error={runs.isError ? errorInfo(runs.error) : null} onRetry={() => void runs.refetch()}
          page={runs.data ? { page, totalPages: runs.data.totalPages ?? 0, totalElements: runs.data.totalElements ?? 0, onPageChange: setPage } : undefined}
          empty={{ title: "No runs yet", description: "Test the connection to record the first run." }} />
      </Card>
      {manage && !i.enabled && <div className="mt-6"><Button variant="ghost" onClick={() => setDeleting(true)}><Trash2 aria-hidden className="h-4 w-4" /> Delete integration</Button></div>}
      <ConfirmDialog open={deleting} onOpenChange={setDeleting} title="Delete integration?" tone="danger" confirmLabel="Delete" busy={remove.isPending}
        description="The configuration and its run history are removed. The deletion is recorded in the audit trail." onConfirm={() => remove.mutate()} />
    </>
  );
}

function ConfigForm({ id, version, adapter, config, secretSet, onDone }: {
  id: string; version: number; adapter: IntegrationAdapterInfo; config: Record<string, string>; secretSet: boolean; onDone: () => void;
}) {
  const api = useApi();
  const a = adapter;
  const [values, setValues] = useState<Record<string, string>>(config);
  const [secret, setSecret] = useState("");
  const [clear, setClear] = useState(false);
  const save = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/integrations/{id}", { params: { path: { id } }, body: { config: values, version, ...(clear ? { secret: "" } : secret ? { secret } : {}) } })),
    onSuccess: onDone,
  });
  return (
    <div className="space-y-3">
      {save.isError && <p role="alert" className="text-sm text-danger">{errorInfo(save.error).message}</p>}
      {a.fields?.map((f) => <Field key={f.key} label={f.label}>{(p) => <Input {...p} value={values[f.key!] ?? ""} onChange={(e) => setValues({ ...values, [f.key!]: e.target.value })} />}</Field>)}
      {a.secretLabel && <Field label={a.secretLabel} hint={secretSet ? "A secret is set. Leave empty to keep it." : "Write-only."}>
        {(p) => <Input {...p} type="password" autoComplete="off" value={secret} disabled={clear} onChange={(e) => setSecret(e.target.value)} />}</Field>}
      {a.secretLabel && secretSet && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={clear} onChange={(e) => setClear(e.target.checked)} /> Remove the stored secret</label>}
      <Button variant="primary" loading={save.isPending} onClick={() => save.mutate()}>Save</Button>
    </div>
  );
}

export default IntegrationDetailPage;
