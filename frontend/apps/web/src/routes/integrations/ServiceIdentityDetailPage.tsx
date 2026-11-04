import { unwrap } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, ConfirmDialog, DataTable, ErrorState, Field, Link, Modal, PageHeader, Select, Skeleton, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, CheckCircle2, KeyRound, Pause, Play, ShieldCheck, Users, XCircle } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "../people/PeopleDialogs";
import { dt, StatusBadge } from "./IntegrationShared";
import { EnvelopePicker, UserSelect } from "./ServiceIdentitiesPage";

/** One service identity: envelope (with independent approval), custodians, roles, API clients, lifecycle. */
export function ServiceIdentityDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { access, me } = useSession();
  const manage = access.has("integration.manage");
  const [dialog, setDialog] = useState<null | "envelope" | "custodians" | "role" | "suspend" | "disable" | "reject">(null);
  const [envelope, setEnvelope] = useState<string[]>([]);
  const [business, setBusiness] = useState("");
  const [technical, setTechnical] = useState("");
  const [roleId, setRoleId] = useState("");
  const [roleUnit, setRoleUnit] = useState("");
  const [reason, setReason] = useState("");
  const q = useQuery({ queryKey: ["service-identities", id], queryFn: async () => unwrap(await api.GET("/api/v1/service-identities/{id}", { params: { path: { id } } })) });
  const roles = useQuery({ queryKey: ["roles", "assignable-all"], enabled: dialog === "role", queryFn: async () =>
    unwrap(await api.GET("/api/v1/roles", { params: { query: { size: 100 } } })) });
  const done = (msg: string) => { void qc.invalidateQueries({ queryKey: ["service-identities"] }); void qc.invalidateQueries({ queryKey: ["api-clients"] }); setDialog(null); setReason(""); toast.show("success", msg); };
  const fail = (e: unknown) => toast.show("danger", "Not done", errorInfo(e).message);
  const v = q.data?.identity;
  const version = v?.version;
  const approve = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/service-identities/{id}/envelope/approve", { params: { path: { id } }, body: { version } })),
    onSuccess: () => done("Envelope approved"), onError: fail });
  const reject = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/service-identities/{id}/envelope/reject", { params: { path: { id } }, body: { reason } })),
    onSuccess: () => done("Proposal rejected"), onError: fail });
  const changeEnvelope = useMutation({ mutationFn: async () => unwrap(await api.PUT("/api/v1/service-identities/{id}/envelope", { params: { path: { id } }, body: { envelope, version } })),
    onSuccess: () => done("Envelope updated"), onError: fail });
  const custodians = useMutation({ mutationFn: async () => unwrap(await api.PUT("/api/v1/service-identities/{id}/custodians", { params: { path: { id } },
    body: { businessCustodianId: business, technicalCustodianId: technical, version } })), onSuccess: () => done("Custodians updated"), onError: fail });
  const assign = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/service-identities/{id}/roles", { params: { path: { id } },
    body: { roleId, ...(roleUnit ? { orgUnitId: roleUnit } : {}) } })), onSuccess: () => done("Role assigned"), onError: fail });
  const revoke = useMutation({ mutationFn: async (assignmentId: string) => unwrap(await api.DELETE("/api/v1/service-identities/{id}/roles/{assignmentId}", { params: { path: { id, assignmentId } } })),
    onSuccess: () => done("Role revoked"), onError: fail });
  const suspend = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/service-identities/{id}/suspend", { params: { path: { id } }, body: { reason } })),
    onSuccess: () => done("Suspended"), onError: fail });
  const resume = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/service-identities/{id}/resume", { params: { path: { id } } })),
    onSuccess: () => done("Resumed"), onError: fail });
  const disable = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/service-identities/{id}/disable", { params: { path: { id } }, body: { reason } })),
    onSuccess: () => done("Disabled"), onError: fail });

  if (q.isError) return <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>;
  if (q.isPending || !v) return <Skeleton className="h-64 w-full" />;
  const proposedByMe = v.proposedBy === me.userId;
  return (
    <>
      <PageHeader title={v.name!} description={v.purpose}
        actions={manage && v.status !== "DISABLED" ? <>
          {v.status === "ACTIVE" && <Button variant="secondary" onClick={() => setDialog("suspend")}><Pause aria-hidden className="h-4 w-4" /> Suspend</Button>}
          {v.status === "SUSPENDED" && <Button variant="primary" loading={resume.isPending} onClick={() => resume.mutate()}><Play aria-hidden className="h-4 w-4" /> Resume</Button>}
          <Button variant="danger" onClick={() => setDialog("disable")}><Ban aria-hidden className="h-4 w-4" /> Disable</Button>
        </> : undefined} />
      {v.status === "PENDING_APPROVAL" && <Alert tone="info" title="Waiting for approval" className="mb-4">Another integration manager must approve the envelope before this identity can be used.</Alert>}
      {v.custodianInvalidSince && <Alert tone="warning" title="A custodian is no longer active" className="mb-4">
        Since {dt(v.custodianInvalidSince)}. Name active custodians within 30 days of that date, or the identity is suspended automatically.</Alert>}
      {v.proposedEnvelope && (
        <Card className="mb-6">
          <CardHeader title="Proposed envelope" description={`Proposed by ${v.proposedByName ?? "—"} on ${dt(v.proposedAt)}. It takes effect only after approval by someone else.`} />
          <CardBody className="flex flex-wrap items-center gap-2">
            {v.proposedEnvelope.map((p) => <Badge key={p} tone={v.scopeEnvelope?.includes(p) ? "neutral" : "warning"}>{p}</Badge>)}
            {manage && <span className="ml-auto flex gap-2">
              <Button variant="primary" disabled={proposedByMe} loading={approve.isPending} onClick={() => approve.mutate()}
                title={proposedByMe ? "You proposed it, so someone else must approve it" : undefined}><CheckCircle2 aria-hidden className="h-4 w-4" /> Approve</Button>
              <Button variant="secondary" onClick={() => setDialog("reject")}><XCircle aria-hidden className="h-4 w-4" /> Reject</Button>
            </span>}
            {proposedByMe && <p className="w-full text-xs text-muted">You proposed this envelope, so another integration manager must approve it.</p>}
          </CardBody>
        </Card>
      )}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <Card>
          <CardHeader title="Governance" />
          <CardBody>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
              <dt className="text-muted">Status</dt><dd data-testid="identity-status"><StatusBadge status={v.status} /> {v.statusReason && <span className="text-xs text-muted">({v.statusReason.replaceAll("_", " ")})</span>}</dd>
              <dt className="text-muted">Business custodian</dt><dd>{v.businessCustodianName}</dd>
              <dt className="text-muted">Technical custodian</dt><dd>{v.technicalCustodianName}</dd>
              <dt className="text-muted">Unit</dt><dd>{v.orgUnitName ?? "Whole organization"}</dd>
              <dt className="text-muted">Envelope approved</dt><dd>{v.approvedByName ? `${v.approvedByName}, ${dt(v.approvedAt)}` : "Not yet"}</dd>
            </dl>
            {manage && v.status !== "DISABLED" && <Button variant="secondary" size="sm" className="mt-4" onClick={() => { setBusiness(v.businessCustodianId ?? ""); setTechnical(v.technicalCustodianId ?? ""); setDialog("custodians"); }}>
              <Users aria-hidden className="h-4 w-4" /> Change custodians</Button>}
          </CardBody>
        </Card>
        <Card className="xl:col-span-2">
          <CardHeader title="Permissions" description="Effective = role grants inside the approved envelope. API clients get at most these, read-only." />
          <CardBody className="space-y-3 text-sm">
            <div><p className="text-muted">Approved envelope</p><div className="mt-1 flex flex-wrap gap-1">{v.scopeEnvelope?.length ? v.scopeEnvelope.map((p) => <Badge key={p}>{p}</Badge>) : "—"}</div></div>
            <div><p className="text-muted">Effective now</p><div className="mt-1 flex flex-wrap gap-1" data-testid="identity-effective">{q.data.effectivePermissions?.length ? q.data.effectivePermissions.map((p) => <Badge key={p} tone="success">{p}</Badge>) : "None"}</div></div>
            {manage && v.status !== "DISABLED" && <Button variant="secondary" size="sm" onClick={() => { setEnvelope(v.scopeEnvelope ?? []); setDialog("envelope"); }}><ShieldCheck aria-hidden className="h-4 w-4" /> Change envelope</Button>}
          </CardBody>
        </Card>
      </div>
      <Card className="mt-6">
        <CardHeader title="Roles" description="Assigned like roles for people (you can only give permissions you hold) and limited to the envelope."
          actions={manage && v.status === "ACTIVE" ? <Button variant="secondary" size="sm" onClick={() => setDialog("role")}><KeyRound aria-hidden className="h-4 w-4" /> Assign role</Button> : undefined} />
        <DataTable caption="Roles" rows={q.data.roles} rowKey={(r) => r.id!} columns={[
          { key: "role", header: "Role", cell: (r) => r.roleName },
          { key: "scope", header: "Scope", cell: (r) => r.orgUnitName ?? "Whole organization" },
          { key: "perms", header: "Permissions", cell: (r) => <span className="text-sm">{r.permissions?.join(", ")}</span> },
          { key: "outside", header: "Outside envelope (ignored)", cell: (r) => r.outsideEnvelope?.length ? <Badge tone="warning">{r.outsideEnvelope.join(", ")}</Badge> : "—", hideOnMobile: true },
          { key: "act", header: "", cell: (r) => manage ? <Button size="sm" variant="ghost" onClick={() => revoke.mutate(r.id!)}>Revoke</Button> : null },
        ]} empty={{ title: "No roles", description: "Assign a role so the identity can read data within its envelope." }} />
      </Card>
      <Card className="mt-6">
        <CardHeader title="API clients" description="Register clients for this identity under API clients." />
        <DataTable caption="API clients of this identity" rows={q.data.clients} rowKey={(c) => c.id!} columns={[
          { key: "name", header: "Name", cell: (c) => <Link to={`/integrations/api-clients/${c.id}`} className="font-medium text-fg hover:underline">{c.name}</Link> },
          { key: "status", header: "Status", cell: (c) => <StatusBadge status={c.status} /> },
          { key: "scopes", header: "Scopes", cell: (c) => <span className="text-sm">{c.scopes?.join(", ")}</span> },
          { key: "used", header: "Last used", cell: (c) => dt(c.lastUsedAt) },
        ]} empty={{ title: "No API clients" }} />
      </Card>

      <Modal open={dialog === "envelope"} onOpenChange={(o) => !o && setDialog(null)} title="Change envelope" size="lg"
        description="Removing permissions applies immediately. Adding any permission creates a proposal that another integration manager must approve."
        footer={<><Button variant="secondary" onClick={() => setDialog(null)}>Cancel</Button><Button variant="primary" loading={changeEnvelope.isPending} disabled={envelope.length === 0} onClick={() => changeEnvelope.mutate()}>Save</Button></>}>
        <EnvelopePicker value={envelope} onChange={setEnvelope} />
      </Modal>
      <Modal open={dialog === "custodians"} onOpenChange={(o) => !o && setDialog(null)} title="Change custodians"
        footer={<><Button variant="secondary" onClick={() => setDialog(null)}>Cancel</Button><Button variant="primary" loading={custodians.isPending} disabled={!business || !technical} onClick={() => custodians.mutate()}>Save</Button></>}>
        <div className="space-y-4"><UserSelect label="Business custodian" value={business} onChange={setBusiness} /><UserSelect label="Technical custodian" value={technical} onChange={setTechnical} /></div>
      </Modal>
      <Modal open={dialog === "role"} onOpenChange={(o) => !o && setDialog(null)} title="Assign role"
        footer={<><Button variant="secondary" onClick={() => setDialog(null)}>Cancel</Button><Button variant="primary" loading={assign.isPending} disabled={!roleId} onClick={() => assign.mutate()}>Assign</Button></>}>
        <div className="space-y-4">
          <Field label="Role" hint="Every permission of the role must be inside the approved envelope.">{(p) => <Select {...p} value={roleId} onChange={(e) => setRoleId(e.target.value)}>
            <option value="">Choose…</option>{roles.data?.items?.filter((r) => r.status === "ACTIVE").map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</Select>}</Field>
          <Field label="Scope">{(p) => <Select {...p} value={roleUnit} onChange={(e) => setRoleUnit(e.target.value)}>
            <option value="">Whole organization</option><UnitOptions permission="integration.read" allowOrganization={false} /></Select>}</Field>
        </div>
      </Modal>
      {(["suspend", "disable", "reject"] as const).map((kind) => (
        <ConfirmDialog key={kind} open={dialog === kind} onOpenChange={(o) => !o && setDialog(null)} tone={kind === "disable" ? "danger" : "primary"}
          title={kind === "suspend" ? "Suspend service identity?" : kind === "disable" ? "Disable service identity?" : "Reject proposed envelope?"}
          description={kind === "suspend" ? "Its API clients stop working until it is resumed." : kind === "disable" ? "Permanent: its roles and API clients are revoked." : "The current envelope stays as it is."}
          confirmLabel={kind === "suspend" ? "Suspend" : kind === "disable" ? "Disable" : "Reject"} busy={suspend.isPending || disable.isPending || reject.isPending}
          onConfirm={() => (kind === "suspend" ? suspend : kind === "disable" ? disable : reject).mutate()}>
          <Field label="Reason (recorded in the audit trail)">{(p) => <TextArea {...p} value={reason} rows={2} maxLength={300} onChange={(e) => setReason(e.target.value)} />}</Field>
        </ConfirmDialog>
      ))}
    </>
  );
}

export default ServiceIdentityDetailPage;
