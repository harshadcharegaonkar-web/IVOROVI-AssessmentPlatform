import { unwrap, type ExamPortal } from "@ivorovi/api-client";
import { Button, Card, DataTable, Field, Input, Modal, PageHeader, Select, Switch, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatNumber } from "../../lib/format";
import { StatusBadge } from "../integrations/IntegrationShared";
import { ExamTabs } from "./ExamShared";

const MFA_LABEL: Record<string, string> = { OFF: "Off", OPTIONAL: "Optional", REQUIRED: "Required" };

/** Exam portals on the shared exam domain (Q-07): the portal address identifies the institution, resolved by the server. */
export function ExamPortalsPage() {
  const api = useApi();
  const [editing, setEditing] = useState<ExamPortal | "new" | null>(null);
  const q = useQuery({ queryKey: ["exam-portals"], queryFn: async () => unwrap(await api.GET("/api/v1/exam/portals")) });
  const columns: Column<ExamPortal>[] = [
    { key: "name", header: "Portal", cell: (p) => <button type="button" className="font-medium text-fg hover:underline" onClick={() => setEditing(p)}>{p.name}</button> },
    { key: "url", header: "Candidate address", cell: (p) => <code className="text-xs">{p.url}</code> },
    { key: "mfa", header: "Authenticator app", cell: (p) => MFA_LABEL[p.mfaPolicy ?? ""] ?? p.mfaPolicy },
    { key: "reg", header: "Self-registration", cell: (p) => (p.selfRegistration ? "Open" : "Closed"), hideOnMobile: true },
    { key: "status", header: "Status", cell: (p) => <StatusBadge status={p.status} />, hideOnMobile: true },
    { key: "candidates", header: "Candidates", cell: (p) => formatNumber(p.candidates), hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title="Examinations" description="Foundations for exam delivery: candidate portals, readiness check-ins with single-use codes, and outage compensation."
        actions={<Button variant="primary" onClick={() => setEditing("new")}><Plus aria-hidden className="h-4 w-4" /> New portal</Button>} />
      <ExamTabs>
        <Card>
          <DataTable caption="Exam portals" columns={columns} rows={q.data} rowKey={(p) => p.id!} loading={q.isPending}
            error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
            empty={{ title: "No exam portal yet", description: "Create one to give candidates an address on the exam domain." }} />
        </Card>
      </ExamTabs>
      {editing && <PortalDialog portal={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function PortalDialog({ portal, onClose }: { portal: ExamPortal | null; onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const [key, setKey] = useState(portal?.portalKey ?? "");
  const [name, setName] = useState(portal?.name ?? "");
  const [mfa, setMfa] = useState(portal?.mfaPolicy ?? "OPTIONAL");
  const [reg, setReg] = useState(portal?.selfRegistration ?? true);
  const [active, setActive] = useState((portal?.status ?? "ACTIVE") === "ACTIVE");
  const save = useMutation({
    mutationFn: async () => portal
      ? unwrap(await api.PUT("/api/v1/exam/portals/{id}", { params: { path: { id: portal.id! } }, body: { name, mfaPolicy: mfa as "OFF", selfRegistration: reg, status: active ? "ACTIVE" : "DISABLED", version: portal.version ?? 0 } }))
      : unwrap(await api.POST("/api/v1/exam/portals", { body: { portalKey: key, name, mfaPolicy: mfa as "OFF", selfRegistration: reg } })),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ["exam-portals"] }); onClose(); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={portal ? "Edit portal" : "New exam portal"}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!name.trim() || (!portal && key.length < 3)} onClick={() => save.mutate()}>Save</Button></>}>
      <div className="space-y-4">
        {save.isError && <p role="alert" className="text-sm text-danger">{errorInfo(save.error).message}</p>}
        {!portal && <Field label="Portal address" hint="Lower-case letters, digits and hyphens; part of the candidate link (…/p/address).">{(p) =>
          <Input {...p} value={key} maxLength={40} onChange={(e) => setKey(e.target.value.toLowerCase())} />}</Field>}
        <Field label="Name shown to candidates">{(p) => <Input {...p} value={name} maxLength={150} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Authenticator app (second factor)" hint="Required: candidates must set one up before checking in. SMS is never the only factor.">{(p) =>
          <Select {...p} value={mfa} onChange={(e) => setMfa(e.target.value as typeof mfa)}>
            <option value="OFF">Off</option><option value="OPTIONAL">Optional</option><option value="REQUIRED">Required</option></Select>}</Field>
        <Switch label="Candidates may create their own exam account" checked={reg} onCheckedChange={setReg} />
        {portal && <Switch label="Portal active" checked={active} onCheckedChange={setActive} />}
      </div>
    </Modal>
  );
}

export default ExamPortalsPage;
