import { unwrap, type ApiClient, type ApiClientCreated } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, Input, Link, Modal, PageHeader, Select, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber, formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "../people/PeopleDialogs";
import { IntegrationTabs, SecretOnce, StatusBadge } from "./IntegrationShared";

/** API clients: read-only machine access with scoped, rotatable keys. */
export function ApiClientsPage() {
  const api = useApi();
  const { access } = useSession();
  const [creating, setCreating] = useState(false);
  const [issued, setIssued] = useState<ApiClientCreated | null>(null);
  const list = useQuery({ queryKey: ["api-clients"], queryFn: async () => unwrap(await api.GET("/api/v1/api-clients")) });
  const columns: Column<ApiClient>[] = [
    { key: "name", header: "Name", cell: (c) => <Link to={`/integrations/api-clients/${c.id}`} className="font-medium text-fg hover:underline">{c.name}</Link> },
    { key: "status", header: "Status", cell: (c) => <StatusBadge status={c.status} /> },
    { key: "for", header: "Acts for", cell: (c) => c.kind === "SERVICE" ? <span className="text-sm">Service: {c.serviceIdentityName}</span> : <span className="text-sm">{c.ownerName}</span> },
    { key: "scopes", header: "Scopes", cell: (c) => <span className="text-sm">{c.scopes?.join(", ")}</span> },
    { key: "unit", header: "Restricted to", cell: (c) => c.orgUnitName ?? "Whole organization", hideOnMobile: true },
    { key: "keys", header: "Active keys", cell: (c) => formatNumber(c.activeKeys), hideOnMobile: true },
    { key: "used", header: "Last used", cell: (c) => formatRelative(c.lastUsedAt) },
    { key: "req", header: "Requests (30 days)", cell: (c) => `${formatNumber(c.requests30Days)}${c.denied30Days ? ` · ${formatNumber(c.denied30Days)} denied` : ""}`, hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title="API clients" description="Read-only access for other systems. Each request is checked against the current permissions of the owner or service identity; never personal, audit or integration data."
        actions={access.has("integration.manage") ? <Button variant="primary" onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> Register client</Button> : undefined} />
      <IntegrationTabs>
        {issued && <div className="mb-4"><SecretOnce label={`API key for ${issued.client?.name}`} value={issued.apiKey!}>
          <p className="mt-2 text-xs">Send it as <code>X-Api-Key</code> on GET requests. <Button size="sm" variant="ghost" onClick={() => setIssued(null)}>I have stored it</Button></p>
        </SecretOnce></div>}
        <Card>
          <DataTable caption="API clients" columns={columns} rows={list.data} rowKey={(c) => c.id!} loading={list.isPending}
            error={list.isError ? errorInfo(list.error) : null} onRetry={() => void list.refetch()}
            empty={{ title: "No API clients", description: "Register one to give another system read access." }} />
        </Card>
      </IntegrationTabs>
      {creating && <ClientDialog onClose={() => setCreating(false)} onIssued={setIssued} />}
    </>
  );
}

function ClientDialog({ onClose, onIssued }: { onClose: () => void; onIssued: (c: ApiClientCreated) => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const scopes = useQuery({ queryKey: ["api-client-scopes"], queryFn: async () => unwrap(await api.GET("/api/v1/api-clients/scopes")) });
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [chosen, setChosen] = useState<string[]>([]);
  const [unit, setUnit] = useState("");
  const [ips, setIps] = useState("");
  const [expires, setExpires] = useState("");
  const [identity, setIdentity] = useState("");
  const identities = useQuery({ queryKey: ["service-identities"], queryFn: async () => unwrap(await api.GET("/api/v1/service-identities")) });
  const chosenIdentity = identities.data?.find((i) => i.id === identity);
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/api-clients", { body: {
      name, description, scopes: chosen, ...(unit ? { orgUnitId: unit } : {}), ...(identity ? { serviceIdentityId: identity } : {}),
      allowedIps: ips.split(/[\s,]+/).filter(Boolean), ...(expires ? { expiresAt: new Date(`${expires}T23:59:59`).toISOString() } : {}),
    } })),
    onSuccess: (c) => { void qc.invalidateQueries({ queryKey: ["api-clients"] }); onIssued(c); onClose(); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="Register API client" size="lg"
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!name.trim() || chosen.length === 0} onClick={() => save.mutate()}>Register</Button></>}>
      <div className="space-y-4">
        {save.isError && <p role="alert" className="text-sm text-danger">{errorInfo(save.error).message}</p>}
        <Field label="Name">{(p) => <Input {...p} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Description (optional)">{(p) => <TextArea {...p} value={description} rows={2} maxLength={300} onChange={(e) => setDescription(e.target.value)} />}</Field>
        <Field label="Acts for" hint={identity ? "Permissions come from the service identity's roles, within its approved envelope." : "The client can never do more than you can do at the time of each request; it stops if you lose access."}>
          {(p) => <Select {...p} value={identity} onChange={(e) => { setIdentity(e.target.value); setChosen([]); }}>
            <option value="">Me (delegated client)</option>
            {identities.data?.filter((i) => i.status === "ACTIVE").map((i) => <option key={i.id} value={i.id}>Service identity: {i.name}</option>)}
          </Select>}
        </Field>
        <fieldset>
          <legend className="mb-2 text-sm font-medium">Scopes (read-only)</legend>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {scopes.data?.filter((s) => !chosenIdentity || chosenIdentity.scopeEnvelope?.includes(s.key!)).map((s) => (
              <label key={s.key} className="flex items-start gap-2 text-sm">
                <input type="checkbox" className="mt-1" checked={chosen.includes(s.key!)} onChange={(e) => setChosen(e.target.checked ? [...chosen, s.key!] : chosen.filter((x) => x !== s.key))} />
                <span><span className="font-medium">{s.name}</span><span className="block text-xs text-muted">{s.key}</span></span>
              </label>
            ))}
          </div>
        </fieldset>
        <Field label="Restrict to unit (optional)">{(p) => <Select {...p} value={unit} onChange={(e) => setUnit(e.target.value)}>
          <option value="">Whole organization</option><UnitOptions permission={chosen[0] ?? "org_unit.read"} allowOrganization={false} /></Select>}</Field>
        <Field label="Allowed IP addresses (optional)" hint="Addresses or CIDR ranges, separated by commas. Empty = any address.">{(p) => <Input {...p} value={ips} placeholder="203.0.113.10, 10.0.0.0/8" onChange={(e) => setIps(e.target.value)} />}</Field>
        <Field label="Expires on (optional)">{(p) => <Input {...p} type="date" value={expires} onChange={(e) => setExpires(e.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

export default ApiClientsPage;
