import { unwrap, type AssessmentSummary } from "@ivorovi/api-client";
import { Alert, Button, ButtonLink, Card, DataTable, Field, Input, Modal, PageHeader, Select, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "../people/PeopleDialogs";
import { AssessmentStateBadge, AssessmentTabs, HIGH_STAKES, INTERNAL_PURPOSES, PURPOSES, PURPOSE_LABEL, STATE_LABEL, marks } from "./AssessmentShared";

/** Assessments the caller may read, newest first, with purpose and state filters. */
export function AssessmentsPage() {
  const api = useApi();
  const { access } = useSession();
  const [purpose, setPurpose] = useState("");
  const [state, setState] = useState("");
  const [text, setText] = useState("");
  const [creating, setCreating] = useState(false);
  const canCreate = access.hasAnywhere("assessment.author") || access.hasAnywhere("assessment.author.internal");
  const q = useQuery({
    queryKey: ["assessments", purpose, state, text],
    queryFn: async () => unwrap(await api.GET("/api/v1/assessments", { params: { query: { purpose: purpose || undefined, state: state || undefined, q: text || undefined } } })),
  });
  const columns: Column<AssessmentSummary>[] = [
    { key: "title", header: "Assessment", cell: (a) => (
      <span className="flex flex-col">
        <ButtonLink variant="ghost" className="h-auto px-0 py-0 text-left font-medium" to={`/assessments/${a.id}`}>{a.title}</ButtonLink>
        <span className="text-xs text-muted">{a.code}</span>
      </span>) },
    { key: "purpose", header: "Purpose", cell: (a) => PURPOSE_LABEL[a.purpose ?? ""] ?? a.purpose, hideOnMobile: true },
    { key: "unit", header: "Institution", cell: (a) => a.orgUnitName ?? "Whole organization", hideOnMobile: true },
    { key: "state", header: "Version", cell: (a) => (
      <span className="flex flex-wrap items-center gap-2">v{a.versionNo} <AssessmentStateBadge state={a.state} />
        {a.publishedVersionNo && a.publishedVersionNo !== a.versionNo && <span className="text-xs text-muted">v{a.publishedVersionNo} published</span>}</span>) },
    { key: "marks", header: "Marks · minutes", cell: (a) => `${marks(a.totalMarks)} · ${a.durationMinutes} min`, hideOnMobile: true },
    { key: "updated", header: "Updated", cell: (a) => formatRelative(a.updatedAt), hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title="Assessments" description="Assessments built from approved questions, reviewed and published by different people. Published versions never change."
        actions={canCreate && <Button variant="primary" onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> New assessment</Button>} />
      <AssessmentTabs>
        <Card>
          <div className="mb-4 grid gap-3 sm:grid-cols-3">
            <Field label="Search">{(p) => <Input {...p} type="search" value={text} maxLength={100} placeholder="Title or code" onChange={(e) => setText(e.target.value)} />}</Field>
            <Field label="Purpose">{(p) => <Select {...p} value={purpose} onChange={(e) => setPurpose(e.target.value)}>
              <option value="">All purposes</option>{PURPOSES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select>}</Field>
            <Field label="State">{(p) => <Select {...p} value={state} onChange={(e) => setState(e.target.value)}>
              <option value="">All states</option>{Object.entries(STATE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select>}</Field>
          </div>
          <DataTable caption="Assessments" columns={columns} rows={q.data} rowKey={(a) => a.id!} loading={q.isPending}
            error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
            empty={{ title: "No assessment yet", description: "An assessment combines fixed questions and random rules over approved questions." }} />
        </Card>
      </AssessmentTabs>
      {creating && <CreateDialog onClose={() => setCreating(false)} />}
    </>
  );
}

function CreateDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const { access } = useSession();
  const navigate = useNavigate();
  const full = access.hasAnywhere("assessment.author");
  const purposes = full ? PURPOSES : PURPOSES.filter(([v]) => INTERNAL_PURPOSES.includes(v));
  const [unit, setUnit] = useState("");
  const [purpose, setPurpose] = useState(purposes[0]![0]);
  const [code, setCode] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [duration, setDuration] = useState(60);
  const permission = INTERNAL_PURPOSES.includes(purpose) && !full ? "assessment.author.internal" : "assessment.author";
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/assessments", {
      body: { orgUnitId: unit || undefined, purpose: purpose as "INTERNAL", code: code.trim(), title: title.trim(), description: description || undefined, durationMinutes: duration },
    })),
    onSuccess: (d) => { onClose(); void navigate(`/assessments/${d.id}/edit`); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="New assessment"
      description="Purpose and institution cannot change later. The purpose decides how many people must take part before publication."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button variant="primary" loading={save.isPending} disabled={!title.trim() || code.trim().length < 2} onClick={() => save.mutate()}>Create draft</Button></>}>
      <div className="space-y-4">
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        <Field label="Purpose" hint={HIGH_STAKES.includes(purpose)
          ? "High-stakes: the author, a reviewer and a publisher must be three different people."
          : "Standard: someone other than the author reviews and publishes."}>
          {(p) => <Select {...p} value={purpose} onChange={(e) => setPurpose(e.target.value)}>{purposes.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select>}
        </Field>
        {!full && <Alert tone="info">You can author internal assessments and practice tests. Examinations are authored by assessment designers.</Alert>}
        <Field label="Institution">{(p) => <Select {...p} value={unit} onChange={(e) => setUnit(e.target.value)}>
          {!access.has(permission) && <option value="">Choose…</option>}<UnitOptions permission={permission} /></Select>}</Field>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Code" hint="Letters, digits, dots, dashes">{(p) => <Input {...p} value={code} maxLength={40} onChange={(e) => setCode(e.target.value)} />}</Field>
          <div className="sm:col-span-2"><Field label="Title">{(p) => <Input {...p} value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />}</Field></div>
        </div>
        <Field label="Description">{(p) => <TextArea {...p} rows={2} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
        <Field label="Duration (minutes)">{(p) => <Input {...p} type="number" min={1} max={600} value={duration} onChange={(e) => setDuration(Number(e.target.value))} />}</Field>
      </div>
    </Modal>
  );
}

export default AssessmentsPage;
