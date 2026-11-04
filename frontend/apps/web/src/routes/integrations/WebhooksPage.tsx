import { unwrap, type WebhookEndpoint } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, Input, Link, Modal, PageHeader, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber, formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { IntegrationTabs, SecretOnce, StatusBadge } from "./IntegrationShared";

/** Webhook endpoints: signed, tenant-scoped event deliveries with retries and failure tracking. */
export function WebhooksPage() {
  const api = useApi();
  const { access } = useSession();
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<{ name: string; value: string } | null>(null);
  const list = useQuery({ queryKey: ["webhooks"], queryFn: async () => unwrap(await api.GET("/api/v1/webhooks")) });
  const columns: Column<WebhookEndpoint>[] = [
    { key: "name", header: "Name", cell: (w) => <Link to={`/integrations/webhooks/${w.id}`} className="font-medium text-fg hover:underline">{w.name}</Link> },
    { key: "url", header: "URL", cell: (w) => <span className="break-all text-sm">{w.url}</span>, hideOnMobile: true },
    { key: "status", header: "Status", cell: (w) => <StatusBadge status={w.status} /> },
    { key: "events", header: "Events", cell: (w) => <span className="text-sm">{w.eventTypes?.join(", ")}</span>, hideOnMobile: true },
    { key: "ok", header: "Delivered (30 days)", cell: (w) => formatNumber(w.delivered30Days) },
    { key: "failed", header: "Failed (30 days)", cell: (w) => formatNumber(w.failed30Days) },
    { key: "pending", header: "Pending", cell: (w) => formatNumber(w.pending), hideOnMobile: true },
    { key: "last", header: "Last success", cell: (w) => formatRelative(w.lastSuccessAt), hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title="Webhooks" description="Notify other systems about changes. Payloads carry identifiers and status only — never names, contact details or other personal data."
        actions={access.has("integration.manage") ? <Button variant="primary" onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New endpoint</Button> : undefined} />
      <IntegrationTabs>
        {secret && <div className="mb-4"><SecretOnce label={`Signing secret for ${secret.name}`} value={secret.value}>
          <p className="mt-2 text-xs">Verify the <code>Ivorovi-Signature</code> header: HMAC-SHA256 of <code>t + "." + body</code>. <Button size="sm" variant="ghost" onClick={() => setSecret(null)}>I have stored it</Button></p>
        </SecretOnce></div>}
        <Card>
          <DataTable caption="Webhook endpoints" columns={columns} rows={list.data} rowKey={(w) => w.id!} loading={list.isPending}
            error={list.isError ? errorInfo(list.error) : null} onRetry={() => void list.refetch()}
            empty={{ title: "No webhook endpoints", description: "Add an HTTPS endpoint to receive signed event notifications." }} />
        </Card>
      </IntegrationTabs>
      {creating && <EndpointDialog onClose={() => setCreating(false)} onCreated={(name, value) => setSecret({ name, value })} />}
    </>
  );
}

export function EndpointDialog({ onClose, onCreated, initial }: {
  onClose: () => void; onCreated?: (name: string, secret: string) => void; initial?: WebhookEndpoint;
}) {
  const api = useApi();
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["webhook-event-types"], queryFn: async () => unwrap(await api.GET("/api/v1/webhooks/event-types")) });
  const [name, setName] = useState(initial?.name ?? "");
  const [url, setUrl] = useState(initial?.url ?? "https://");
  const [events, setEvents] = useState<string[]>(initial?.eventTypes ?? []);
  const [ids, setIds] = useState(initial?.includeIdentifiers ?? false);
  const save = useMutation({
    mutationFn: async () => initial
      ? { edited: unwrap(await api.PUT("/api/v1/webhooks/{id}", { params: { path: { id: initial.id! } }, body: { name, url, eventTypes: events, includeIdentifiers: ids, version: initial.version } })) }
      : { created: unwrap(await api.POST("/api/v1/webhooks", { body: { name, url, eventTypes: events, includeIdentifiers: ids } })) },
    onSuccess: (r) => { void qc.invalidateQueries({ queryKey: ["webhooks"] }); if ("created" in r && r.created) onCreated?.(r.created.endpoint!.name!, r.created.signingSecret!); onClose(); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={initial ? "Edit endpoint" : "New webhook endpoint"} size="lg"
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!name.trim() || events.length === 0} onClick={() => save.mutate()}>{initial ? "Save" : "Create"}</Button></>}>
      <div className="space-y-4">
        {save.isError && <p role="alert" className="text-sm text-danger">{errorInfo(save.error).message}</p>}
        <Field label="Name">{(p) => <Input {...p} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Endpoint URL" hint="HTTPS only; private and internal addresses are refused.">{(p) => <Input {...p} type="url" value={url} maxLength={500} onChange={(e) => setUrl(e.target.value)} />}</Field>
        <fieldset>
          <legend className="mb-2 text-sm font-medium">Events</legend>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {types.data?.map((t) => (
              <label key={t.key} className="flex items-start gap-2 text-sm">
                <input type="checkbox" className="mt-1" checked={events.includes(t.key!)} onChange={(e) => setEvents(e.target.checked ? [...events, t.key!] : events.filter((x) => x !== t.key))} />
                <span><span className="font-medium">{t.label}</span><span className="block text-xs text-muted">{t.description}</span></span>
              </label>
            ))}
          </div>
        </fieldset>
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" checked={ids} onChange={(e) => setIds(e.target.checked)} />
          <span><span className="font-medium">Include student, staff and admission numbers</span>
            <span className="block text-xs text-muted">Off by default. Names, contact details and dates of birth are never sent.</span></span>
        </label>
      </div>
    </Modal>
  );
}

export default WebhooksPage;
