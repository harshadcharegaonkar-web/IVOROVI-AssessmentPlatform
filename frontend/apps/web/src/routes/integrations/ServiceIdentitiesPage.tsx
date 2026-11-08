import { unwrap, type ServiceIdentity } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, Input, Link, Modal, PageHeader, Select, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "../people/PeopleDialogs";
import { IntegrationTabs, StatusBadge } from "./IntegrationShared";

/** Active users for custodian and owner pickers (people only; service identities are not users). */
export function useActiveUsers() {
  const api = useApi();
  return useQuery({ queryKey: ["users", "active-picker"], queryFn: async () =>
    unwrap(await api.GET("/api/v1/users", { params: { query: { status: "ACTIVE", size: 100, sort: "name", direction: "asc" } } })) });
}

export function UserSelect({ label, value, onChange, hint }: { label: string; value: string; onChange: (v: string) => void; hint?: string }) {
  const users = useActiveUsers();
  return (
    <Field label={label} hint={hint}>{(p) => (
      <Select {...p} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Choose a person…</option>
        {users.data?.items?.map((u) => <option key={u.id} value={u.id}>{u.displayName} ({u.email})</option>)}
      </Select>)}</Field>
  );
}

/** Service identities: independently governed, read-only machine identities for long-running integrations (KI-59). */
export function ServiceIdentitiesPage() {
  const api = useApi();
  const { access } = useSession();
  const [creating, setCreating] = useState(false);
  const list = useQuery({ queryKey: ["service-identities"], queryFn: async () => unwrap(await api.GET("/api/v1/service-identities")) });
  const columns: Column<ServiceIdentity>[] = [
    { key: "name", header: "Name", cell: (s) => <Link to={`/integrations/service-identities/${s.id}`} className="font-medium text-fg hover:underline">{s.name}</Link> },
    { key: "status", header: "Status", cell: (s) => <StatusBadge status={s.status} /> },
    { key: "envelope", header: "Approved envelope", cell: (s) => <span className="text-sm">{s.scopeEnvelope?.length ? s.scopeEnvelope.join(", ") : "—"}</span> },
    { key: "pending", header: "Awaiting approval", cell: (s) => (s.proposedEnvelope ? "Yes" : "—"), hideOnMobile: true },
    { key: "custodians", header: "Custodians", cell: (s) => <span className="text-sm">{s.businessCustodianName} · {s.technicalCustodianName}</span>, hideOnMobile: true },
    { key: "clients", header: "API clients", cell: (s) => formatNumber(s.activeClients), hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title="Service identities" description="Read-only identities for long-running integrations. They do not depend on any one employee: two custodians are accountable, and a second integration manager approves what they may ever access."
        actions={access.has("integration.manage") ? <Button variant="primary" onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New service identity</Button> : undefined} />
      <IntegrationTabs>
        <Card>
          <DataTable caption="Service identities" columns={columns} rows={list.data} rowKey={(s) => s.id!} loading={list.isPending}
            error={list.isError ? errorInfo(list.error) : null} onRetry={() => void list.refetch()}
            empty={{ title: "No service identities", description: "Create one for an integration that must keep working when staff change." }} />
        </Card>
      </IntegrationTabs>
      {creating && <CreateDialog onClose={() => setCreating(false)} />}
    </>
  );
}

export function EnvelopePicker({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  const api = useApi();
  const scopes = useQuery({ queryKey: ["api-client-scopes"], queryFn: async () => unwrap(await api.GET("/api/v1/api-clients/scopes")) });
  return (
    <fieldset>
      <legend className="mb-2 text-sm font-medium">Scope envelope (read-only permissions it may ever hold)</legend>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {scopes.data?.map((s) => (
          <label key={s.key} className="flex items-start gap-2 text-sm">
            <input type="checkbox" className="mt-1" checked={value.includes(s.key!)} onChange={(e) => onChange(e.target.checked ? [...value, s.key!] : value.filter((x) => x !== s.key))} />
            <span><span className="font-medium">{s.name}</span><span className="block text-xs text-muted">{s.key}</span></span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

function CreateDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [purpose, setPurpose] = useState("");
  const [unit, setUnit] = useState("");
  const [business, setBusiness] = useState("");
  const [technical, setTechnical] = useState("");
  const [envelope, setEnvelope] = useState<string[]>([]);
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/service-identities", { body: { name, purpose, ...(unit ? { orgUnitId: unit } : {}),
      businessCustodianId: business, technicalCustodianId: technical, envelope } })),
    onSuccess: (s) => { void qc.invalidateQueries({ queryKey: ["service-identities"] }); onClose(); void navigate(`/integrations/service-identities/${s.id}`); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="New service identity" size="lg"
      description="It stays pending until another integration manager approves its envelope."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button variant="primary" loading={save.isPending} disabled={!name.trim() || !purpose.trim() || !business || !technical || business === technical || envelope.length === 0} onClick={() => save.mutate()}>Submit for approval</Button></>}>
      <div className="space-y-4">
        {save.isError && <p role="alert" className="text-sm text-danger">{errorInfo(save.error).message}</p>}
        <Field label="Name">{(p) => <Input {...p} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Purpose" hint="Which integration uses it and why.">{(p) => <TextArea {...p} value={purpose} rows={2} maxLength={500} onChange={(e) => setPurpose(e.target.value)} />}</Field>
        <Field label="Unit (optional)">{(p) => <Select {...p} value={unit} onChange={(e) => setUnit(e.target.value)}>
          <option value="">Whole organization</option><UnitOptions permission="integration.read" allowOrganization={false} /></Select>}</Field>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <UserSelect label="Business custodian" value={business} onChange={setBusiness} hint="Accountable for why the integration needs this data." />
          <UserSelect label="Technical custodian" value={technical} onChange={setTechnical} hint="Accountable for operating the integration and its keys." />
        </div>
        {business && business === technical && <p role="alert" className="text-sm text-danger">The business and technical custodians must be two different people.</p>}
        <EnvelopePicker value={envelope} onChange={setEnvelope} />
      </div>
    </Modal>
  );
}

export default ServiceIdentitiesPage;
