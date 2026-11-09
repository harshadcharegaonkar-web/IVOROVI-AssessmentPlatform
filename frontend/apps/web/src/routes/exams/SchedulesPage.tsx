import { unwrap, type ExamSchedule } from "@ivorovi/api-client";
import { Alert, Badge, Button, ButtonLink, Card, DataTable, Field, Input, Modal, PageHeader, Select, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { UnitOptions } from "../people/PeopleDialogs";

/** Scheduled examinations: a published assessment version, a portal, a window and its enrolled candidates. */
export function SchedulesPage() {
  const api = useApi();
  const { access } = useSession();
  const [creating, setCreating] = useState(false);
  const q = useQuery({ queryKey: ["exam-schedules"], queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules")) });
  const columns: Column<ExamSchedule>[] = [
    { key: "t", header: "Examination", cell: (s) => (
      <span className="flex flex-col">
        <ButtonLink variant="ghost" className="h-auto px-0 py-0 text-left font-medium" to={`/exams/schedules/${s.id}`}>{s.title}</ButtonLink>
        <span className="text-xs text-muted">{s.assessmentCode} · version {s.versionNo}</span>
      </span>) },
    { key: "w", header: "Window", cell: (s) => `${formatDateTime(s.opensAt)} – ${formatDateTime(s.closesAt)}` },
    { key: "u", header: "Institution", cell: (s) => s.orgUnitName ?? "Whole organization", hideOnMobile: true },
    { key: "c", header: "Candidates", cell: (s) => `${s.enrolled} enrolled · ${s.invited} invited · ${s.checkedIn} checked in`, hideOnMobile: true },
    { key: "s", header: "Status", cell: (s) => <Badge tone={s.status === "SCHEDULED" ? "success" : "neutral"}>{s.status === "SCHEDULED" ? "Scheduled" : "Cancelled"}</Badge> },
  ];
  return (
    <>
      <PageHeader title="Exam schedules" description="Each schedule uses one published assessment version. Later publications never change it."
        actions={access.hasAnywhere("exam.schedule.manage") && <Button variant="primary" onClick={() => setCreating(true)}><Plus aria-hidden className="h-4 w-4" /> Schedule examination</Button>} />
      <Card>
        <DataTable caption="Exam schedules" columns={columns} rows={q.data} rowKey={(s) => s.id!} loading={q.isPending}
          error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
          empty={{ title: "No examination scheduled", description: "Schedule a published assessment, then enrol and invite candidates." }} />
      </Card>
      {creating && <CreateDialog onClose={() => setCreating(false)} />}
    </>
  );
}

const local = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);

function CreateDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const sources = useQuery({ queryKey: ["schedule-sources"], queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules/sources")) });
  const portals = useQuery({ queryKey: ["schedule-portals"], queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules/portals")) });
  const [versionId, setVersionId] = useState("");
  const [portalId, setPortalId] = useState("");
  const [unit, setUnit] = useState("");
  const [title, setTitle] = useState("");
  const [opens, setOpens] = useState(() => local(new Date(Date.now() + 60 * 60_000)));
  const [closes, setCloses] = useState(() => local(new Date(Date.now() + 4 * 3600_000)));
  const [late, setLate] = useState("");
  const [instructions, setInstructions] = useState("");
  const [calculator, setCalculator] = useState(false);
  const version = (sources.data ?? []).find((v) => v.versionId === versionId);
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/schedules", { body: {
      versionId, portalId, orgUnitId: unit || undefined, title: title || undefined, opensAt: new Date(opens).toISOString(), closesAt: new Date(closes).toISOString(),
      lateEntryUntil: late ? new Date(late).toISOString() : undefined, instructions: instructions || undefined, calculator,
    } })),
    onSuccess: (s) => { void qc.invalidateQueries({ queryKey: ["exam-schedules"] }); onClose(); void navigate(`/exams/schedules/${s.id}`); },
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="Schedule an examination"
      description="Only the currently published version can be scheduled. The schedule keeps that exact version."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button variant="primary" loading={save.isPending} disabled={!versionId || !portalId} onClick={() => save.mutate()}>Schedule</Button></>}>
      <div className="space-y-4">
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        <Field label="Published assessment">{(p) => <Select {...p} value={versionId} onChange={(e) => setVersionId(e.target.value)}>
          <option value="">Choose…</option>{(sources.data ?? []).map((v) => <option key={v.versionId} value={v.versionId}>{v.title} ({v.code} · v{v.versionNo} · {v.durationMinutes} min)</option>)}</Select>}</Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Exam portal">{(p) => <Select {...p} value={portalId} onChange={(e) => setPortalId(e.target.value)}>
            <option value="">Choose…</option>{(portals.data ?? []).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</Select>}</Field>
          <Field label="Institution">{(p) => <Select {...p} value={unit} onChange={(e) => setUnit(e.target.value)}><option value="">Choose…</option><UnitOptions permission="exam.schedule.manage" /></Select>}</Field>
        </div>
        <Field label="Title" hint={version ? `Default: ${version.title}` : undefined}>{(p) => <Input {...p} value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />}</Field>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Opens">{(p) => <Input {...p} type="datetime-local" value={opens} onChange={(e) => setOpens(e.target.value)} />}</Field>
          <Field label="Closes">{(p) => <Input {...p} type="datetime-local" value={closes} onChange={(e) => setCloses(e.target.value)} />}</Field>
          <Field label="Late entry until" hint="Optional">{(p) => <Input {...p} type="datetime-local" value={late} onChange={(e) => setLate(e.target.value)} />}</Field>
        </div>
        <Field label="Instructions for candidates">{(p) => <TextArea {...p} rows={2} maxLength={4000} value={instructions} onChange={(e) => setInstructions(e.target.value)} />}</Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={calculator} onChange={(e) => setCalculator(e.target.checked)} />A calculator is allowed</label>
      </div>
    </Modal>
  );
}

export default SchedulesPage;
