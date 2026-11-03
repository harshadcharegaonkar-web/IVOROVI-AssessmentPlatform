import { unwrap, type Guardian, type StudentDetail } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardHeader, ConfirmDialog, EmptyState, ErrorState, Field, Input, Modal, Select, Skeleton, Switch, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useIdempotencyKey } from "../academics/AcademicsShared";
import { RELATIONSHIPS, titleCase } from "./studentModel";
import { useInvalidateStudents } from "./StudentShared";

/** Guardian management. Guardian details are personal information; every view is audited. */
export function GuardiansTab({ detail }: { detail: StudentDetail }) {
  const api = useApi();
  const s = detail.student!;
  const manage = !!detail.can?.manage && !!s.active;
  const [editing, setEditing] = useState<Guardian | "new" | null>(null);
  const [removing, setRemoving] = useState<Guardian | null>(null);
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const q = useQuery({ queryKey: ["students", "guardians", s.id], queryFn: async () => unwrap(await api.GET("/api/v1/students/{studentId}/guardians", { params: { path: { studentId: s.id! } } })) });
  const remove = useMutation({
    mutationFn: async (g: Guardian) => unwrap(await api.DELETE("/api/v1/students/{studentId}/guardians/{guardianId}", { params: { path: { studentId: s.id!, guardianId: g.id! } } })),
    onSuccess: () => { invalidate(); setRemoving(null); toast.show("success", "Guardian removed"); },
    onError: (e) => { setRemoving(null); toast.show("danger", "Could not remove", errorInfo(e).message); },
  });
  return (
    <Card>
      <CardHeader title="Guardians" description="Personal information: shown only to people allowed to see personal details; stored encrypted."
        actions={manage && (q.data?.length ?? 0) < 6 ? <Button size="sm" onClick={() => setEditing("new")}><Plus aria-hidden className="h-4 w-4" /> Add guardian</Button> : undefined} />
      {q.isPending ? <div className="p-6"><Skeleton className="h-24 w-full" /></div> : q.isError ? <ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />
        : q.data.length === 0 ? <EmptyState title="No guardians recorded" description="Add a parent or guardian with a mobile number or email address." /> : (
          <ul className="divide-y divide-border" data-testid="guardian-list">
            {q.data.map((g) => (
              <li key={g.id} className="flex flex-wrap items-start justify-between gap-3 px-5 py-4 sm:px-6">
                <div className="min-w-0 text-sm">
                  <p className="flex flex-wrap items-center gap-2 font-medium text-fg">{g.name} <span className="font-normal text-muted">({titleCase(g.relationship)})</span>
                    {g.primary && <Badge tone="info">Primary</Badge>}{g.emergency && <Badge tone="warning">Emergency contact</Badge>}</p>
                  <p className="mt-1 text-muted-strong">{[g.mobile, g.email].filter(Boolean).join(" · ")}</p>
                  {g.address && <p className="mt-0.5 whitespace-pre-line text-muted">{g.address}</p>}
                </div>
                {manage && <div className="flex gap-1">
                  <Button size="sm" variant="ghost" aria-label={`Edit ${g.name}`} onClick={() => setEditing(g)}><Pencil aria-hidden className="h-4 w-4" /></Button>
                  <Button size="sm" variant="ghost" aria-label={`Remove ${g.name}`} onClick={() => setRemoving(g)}><Trash2 aria-hidden className="h-4 w-4" /></Button>
                </div>}
              </li>
            ))}
          </ul>
        )}
      {editing && <GuardianDialog studentId={s.id!} guardian={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog open={!!removing} onOpenChange={(o) => !o && setRemoving(null)} title={`Remove ${removing?.name}?`} tone="danger" confirmLabel="Remove"
        busy={remove.isPending} onConfirm={() => removing && remove.mutate(removing)} description="The guardian's details are deleted; the change is recorded in the student's history." />
    </Card>
  );
}

function GuardianDialog({ studentId, guardian, onClose }: { studentId: string; guardian?: Guardian | undefined; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const key = useIdempotencyKey();
  const [v, setV] = useState({ name: guardian?.name ?? "", relationship: guardian?.relationship ?? "MOTHER", mobile: guardian?.mobile ?? "", email: guardian?.email ?? "",
    address: guardian?.address ?? "", primary: guardian?.primary ?? false, emergency: guardian?.emergency ?? false });
  const [problem, setProblem] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: async () => {
      const body = { name: v.name.trim(), relationship: v.relationship, primary: v.primary, emergency: v.emergency,
        ...(v.mobile.trim() ? { mobile: v.mobile.trim() } : {}), ...(v.email.trim() ? { email: v.email.trim() } : {}), ...(v.address.trim() ? { address: v.address.trim() } : {}) };
      if (guardian) return unwrap(await api.PATCH("/api/v1/students/{studentId}/guardians/{guardianId}", { params: { path: { studentId, guardianId: guardian.id! } }, body: { ...body, version: guardian.version! } }));
      return unwrap(await api.POST("/api/v1/students/{studentId}/guardians", { params: { path: { studentId } }, headers: { "Idempotency-Key": key.get() }, body }));
    },
    onSuccess: () => { invalidate(); toast.show("success", guardian ? "Guardian saved" : "Guardian added"); onClose(); },
    onError: (e) => { key.rotate(); setProblem(errorInfo(e).message); },
  });
  const set = (k: keyof typeof v, value: string | boolean) => setV((x) => ({ ...x, [k]: value }));
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={guardian ? `Edit ${guardian.name}` : "Add guardian"} description="Give at least a mobile number or an email address."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={run.isPending} disabled={!v.name.trim() || (!v.mobile.trim() && !v.email.trim())}
        onClick={() => { setProblem(null); run.mutate(); }}>{guardian ? "Save" : "Add"}</Button></>}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {problem && <Alert tone="danger" className="sm:col-span-2">{problem}</Alert>}
        <Field label="Name">{(f) => <Input {...f} value={v.name} maxLength={200} onChange={(e) => set("name", e.target.value)} />}</Field>
        <Field label="Relationship">{(f) => <Select {...f} value={v.relationship} onChange={(e) => set("relationship", e.target.value)}>
          {RELATIONSHIPS.map((r) => <option key={r} value={r}>{titleCase(r)}</option>)}</Select>}</Field>
        <Field label="Mobile">{(f) => <Input {...f} type="tel" value={v.mobile} maxLength={20} onChange={(e) => set("mobile", e.target.value)} />}</Field>
        <Field label="Email">{(f) => <Input {...f} type="email" value={v.email} maxLength={320} onChange={(e) => set("email", e.target.value)} />}</Field>
        <div className="sm:col-span-2"><Field label="Address (optional)">{(f) => <TextArea {...f} rows={2} value={v.address} maxLength={500} onChange={(e) => set("address", e.target.value)} />}</Field></div>
        <div className="flex items-center gap-2"><Switch id="g-primary" checked={v.primary} onCheckedChange={(c) => set("primary", c)} label="Primary guardian" /><label htmlFor="g-primary" className="text-sm">Primary guardian</label></div>
        <div className="flex items-center gap-2"><Switch id="g-emergency" checked={v.emergency} onCheckedChange={(c) => set("emergency", c)} label="Emergency contact" /><label htmlFor="g-emergency" className="text-sm">Emergency contact</label></div>
      </div>
    </Modal>
  );
}
