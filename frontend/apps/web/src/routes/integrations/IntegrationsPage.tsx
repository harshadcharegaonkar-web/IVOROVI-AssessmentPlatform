import { unwrap, type IntegrationAdapterInfo } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, Input, Link, Modal, PageHeader, Select, TextArea, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative, humanize } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { IntegrationTabs, StatusBadge } from "./IntegrationShared";

type Integration = NonNullable<Awaited<ReturnType<typeof useList>>["data"]>[number];
const KINDS = ["SIS_ERP", "LMS", "EMAIL", "APPLICATION"] as const;
const KIND_LABEL: Record<string, string> = { SIS_ERP: "SIS / ERP", LMS: "Learning management", EMAIL: "Email provider", APPLICATION: "Institutional application" };

function useList() {
  const api = useApi();
  return useQuery({ queryKey: ["integrations"], queryFn: async () => unwrap(await api.GET("/api/v1/integrations")) });
}

/** Integrations: configured connections with status, last check, last success and last sync. */
export function IntegrationsPage() {
  const { access } = useSession();
  const list = useList();
  const [creating, setCreating] = useState(false);
  const columns: Column<Integration>[] = [
    { key: "name", header: "Name", cell: (i) => <Link to={`/integrations/${i.id}`} className="font-medium text-fg hover:underline">{i.name}</Link> },
    { key: "kind", header: "Kind", cell: (i) => KIND_LABEL[i.kind ?? ""] ?? i.kind },
    { key: "adapter", header: "Adapter", cell: (i) => i.adapterLabel, hideOnMobile: true },
    { key: "enabled", header: "Enabled", cell: (i) => (i.enabled ? "Yes" : "No") },
    { key: "status", header: "Connection", cell: (i) => <StatusBadge status={i.connectionStatus} /> },
    { key: "checked", header: "Last check", cell: (i) => formatRelative(i.lastCheckedAt), hideOnMobile: true },
    { key: "success", header: "Last success", cell: (i) => formatRelative(i.lastSuccessAt), hideOnMobile: true },
    { key: "sync", header: "Last sync", cell: (i) => formatRelative(i.lastSyncAt), hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title="Integrations" description="Connections to student information, ERP, learning management, email and other institutional systems through built-in, vendor-neutral adapters."
        actions={access.has("integration.manage") ? <Button variant="primary" onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New integration</Button> : undefined} />
      <IntegrationTabs>
        <Card>
          <DataTable caption="Integrations" columns={columns} rows={list.data} rowKey={(i) => i.id!} loading={list.isPending}
            error={list.isError ? errorInfo(list.error) : null} onRetry={() => void list.refetch()}
            empty={{ title: "No integrations yet", description: "Add one to check connectivity with another system. Vendor-specific connectors are not part of this release." }} />
        </Card>
      </IntegrationTabs>
      {creating && <IntegrationDialog onClose={() => setCreating(false)} />}
    </>
  );
}

/** Create dialog: kind → adapter → the adapter's declared fields and its write-only secret. */
function IntegrationDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const adapters = useQuery({ queryKey: ["integration-adapters"], queryFn: async () => unwrap(await api.GET("/api/v1/integrations/adapters")) });
  const [kind, setKind] = useState<(typeof KINDS)[number]>("SIS_ERP");
  const [adapterKey, setAdapterKey] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [config, setConfig] = useState<Record<string, string>>({});
  const [secret, setSecret] = useState("");
  const usable = (adapters.data ?? []).filter((a) => a.kinds?.includes(kind));
  const adapter: IntegrationAdapterInfo | undefined = usable.find((a) => a.key === adapterKey) ?? usable[0];
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/integrations", { body: { kind, adapter: adapter?.key, name, description, config, ...(secret ? { secret } : {}) } })),
    onSuccess: (i) => { void qc.invalidateQueries({ queryKey: ["integrations"] }); toast.show("success", "Integration created", "It stays disabled until you enable it."); onClose(); void navigate(`/integrations/${i.id}`); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="New integration" size="lg"
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!adapter || !name.trim()} onClick={() => save.mutate()}>Create</Button></>}>
      <div className="space-y-4">
        {save.isError && <p role="alert" className="text-sm text-danger">{errorInfo(save.error).message}</p>}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Kind">{(p) => <Select {...p} value={kind} onChange={(e) => { setKind(e.target.value as typeof kind); setAdapterKey(""); setConfig({}); }}>
            {KINDS.map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}</Select>}</Field>
          <Field label="Adapter">{(p) => <Select {...p} value={adapter?.key ?? ""} onChange={(e) => { setAdapterKey(e.target.value); setConfig({}); }}>
            {usable.map((a) => <option key={a.key} value={a.key}>{a.label}</option>)}</Select>}</Field>
        </div>
        {adapter && <p className="text-sm text-muted">{adapter.description}</p>}
        <Field label="Name">{(p) => <Input {...p} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Description (optional)">{(p) => <TextArea {...p} value={description} maxLength={300} rows={2} onChange={(e) => setDescription(e.target.value)} />}</Field>
        {adapter?.fields?.map((f) => (
          <Field key={f.key} label={`${f.label}${f.required ? "" : " (optional)"}`} hint={f.help ?? undefined}>{(p) => f.type === "select" ? (
            <Select {...p} value={config[f.key!] ?? ""} onChange={(e) => setConfig({ ...config, [f.key!]: e.target.value })}>
              <option value="">Choose…</option>{f.options?.map((o) => <option key={o} value={o}>{humanize(o)}</option>)}
            </Select>
          ) : <Input {...p} type={f.type === "number" ? "number" : f.type === "url" ? "url" : "text"} value={config[f.key!] ?? ""} onChange={(e) => setConfig({ ...config, [f.key!]: e.target.value })} />}</Field>
        ))}
        {adapter?.secretLabel && <Field label={`${adapter.secretLabel}${adapter.secretRequired ? "" : " (if the authentication needs one)"}`} hint="Write-only: stored encrypted and never shown again.">
          {(p) => <Input {...p} type="password" autoComplete="off" value={secret} onChange={(e) => setSecret(e.target.value)} />}</Field>}
      </div>
    </Modal>
  );
}

export default IntegrationsPage;
