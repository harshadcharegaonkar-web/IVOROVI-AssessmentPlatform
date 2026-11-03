import { unwrap, type FacultyAssignment } from "@ivorovi/api-client";
import { Alert, Badge, Breadcrumbs, Button, Card, CardBody, CardHeader, DataTable, Field, Input, Link, Menu, MenuItem, Modal, PageHeader, Select, Skeleton, Switch, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { MoreHorizontal, Star, UserPlus } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, Facts, StatusBadge, StatusDialog, useIdempotencyKey, useInvalidateAcademics } from "./AcademicsShared";
import { formatDate, offeringTransitions, statusLabel } from "./academicsModel";

/** A subject offering and its faculty assignment (multiple faculty, one primary, dated, with history). */
export function OfferingDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const labels = useOrgLabels();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const [history, setHistory] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [ending, setEnding] = useState<FacultyAssignment | null>(null);
  const [target, setTarget] = useState<"PLANNED" | "ACTIVE" | "COMPLETED" | "CANCELLED" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const q = useQuery({ queryKey: ["academics", "offering", id], queryFn: async () => unwrap(await api.GET("/api/v1/academics/subject-offerings/{id}", { params: { path: { id } } })) });
  const faculty = useQuery({ queryKey: ["academics", "faculty", id, history], enabled: q.isSuccess,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/subject-offerings/{id}/faculty", { params: { path: { id }, query: { includeHistory: history } } })) });
  const status = useMutation({
    mutationFn: async (reason: string) => unwrap(await api.POST("/api/v1/academics/subject-offerings/{id}/status", { params: { path: { id } },
      body: { status: target!, version: q.data!.version!, ...(reason ? { reason } : {}) } })),
    onSuccess: (o) => { invalidate(); setTarget(null); toast.show("success", `Now ${statusLabel(o.status).toLowerCase()}`, `${o.subjectCode} · ${o.batchCode}`); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const primary = useMutation({
    mutationFn: async (assignmentId: string) => unwrap(await api.POST("/api/v1/academics/subject-offerings/{id}/faculty/{assignmentId}/primary", { params: { path: { id, assignmentId } } })),
    onSuccess: () => { invalidate(); toast.show("success", "Primary faculty changed"); },
    onError: (e) => toast.show("danger", "Could not change", errorInfo(e).message),
  });
  const end = useMutation({
    mutationFn: async (reason: string) => unwrap(await api.POST("/api/v1/academics/subject-offerings/{id}/faculty/{assignmentId}/end", { params: { path: { id, assignmentId: ending!.id! } }, body: { reason } })),
    onSuccess: () => { invalidate(); setEnding(null); toast.show("success", "Assignment ended", "Kept in the history."); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) return <DetailError error={q.error} what="Subject offering" onRetry={() => void q.refetch()} />;
  const o = q.data;
  const open = o.status === "PLANNED" || o.status === "ACTIVE";
  const canAssign = !!o.canAssignFaculty && open;
  const columns: Column<FacultyAssignment>[] = [
    { key: "name", header: "Faculty", cell: (f) => <span className="block min-w-0"><span className="flex items-center gap-2 truncate font-medium">{f.staffName}{f.primary && <Badge tone="info">Primary</Badge>}</span>
      <span className="block truncate text-xs text-muted">{f.staffNo}{f.designation ? ` · ${f.designation}` : ""}</span></span> },
    { key: "period", header: "Period", hideOnMobile: true, cell: (f) => `${formatDate(f.startsOn)} – ${f.endsOn ? formatDate(f.endsOn) : "open"}` },
    { key: "state", header: "Status", cell: (f) => f.current ? <Badge tone="success">Current</Badge> : <span className="block min-w-0"><Badge>Ended</Badge><span className="mt-1 block truncate text-xs text-muted">{f.endReason}</span></span> },
    ...(canAssign ? [{ key: "actions", header: "Actions", cell: (f: FacultyAssignment) => f.current ? (
      <Menu trigger={<Button size="sm" variant="ghost" aria-label={`Actions for ${f.staffName}`}><MoreHorizontal aria-hidden className="h-4 w-4" /></Button>}>
        {!f.primary && <MenuItem onSelect={() => primary.mutate(f.id!)}><Star aria-hidden className="h-4 w-4" /> Make primary</MenuItem>}
        <MenuItem tone="danger" onSelect={() => { setProblem(null); setEnding(f); }}>End assignment…</MenuItem>
      </Menu>) : null }] satisfies Column<FacultyAssignment>[] : []),
  ];
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: "Subject offerings", to: "/academics/offerings" }, { label: `${o.subjectCode} · ${o.batchCode}` }]} /></div>
      <PageHeader title={`${o.subjectCode} · ${o.subjectName}`} description={<span className="inline-flex flex-wrap items-center gap-2">{o.batchName}<StatusBadge status={o.status} /></span>}
        actions={o.canManage && offeringTransitions(o.status).length > 0 ? (
          <Menu trigger={<Button variant="secondary"><MoreHorizontal aria-hidden className="h-4 w-4" /> Status</Button>}>
            {offeringTransitions(o.status).map((s) => <MenuItem key={s} tone={s === "CANCELLED" ? "danger" : undefined} onSelect={() => { setProblem(null); setTarget(s); }}>{s === "CANCELLED" ? "Cancel offering…" : `Mark ${statusLabel(s).toLowerCase()}`}</MenuItem>)}
          </Menu>) : undefined} />
      <div className="space-y-6">
        <Card><CardHeader title="Details" /><CardBody>
          <Facts items={[
            { label: labels.singular("SUBJECT"), value: <Link to={`/academics/subjects/${o.subjectId}`} className="text-primary underline-offset-2 hover:underline">{o.subjectCode} · {o.subjectName}</Link> },
            { label: labels.singular("BATCH"), value: <Link to={`/academics/batches/${o.batchId}`} className="text-primary underline-offset-2 hover:underline">{o.batchName} ({o.batchCode})</Link> },
            { label: labels.singular("COURSE"), value: <Link to={`/academics/courses/${o.courseId}`} className="text-primary underline-offset-2 hover:underline">{o.courseCode}</Link> },
            { label: "Calendar", value: `${o.academicYearName} · ${o.termName}` },
            { label: `${labels.singular("COURSE")} ${labels.singular("TERM").toLowerCase()}`, value: o.termNumber },
            { label: "Section", value: o.section || "—" }, { label: "Credits", value: o.credits },
          ]} />
        </CardBody></Card>
        <Card>
          <CardHeader title="Faculty assignment" description="Staff profiles teaching this offering. One can be primary; ended assignments stay in the history."
            actions={<div className="flex flex-wrap items-center gap-3">
              <span className="flex items-center gap-2 text-sm"><Switch id="history" checked={history} onCheckedChange={setHistory} label="Show ended assignments" /><label htmlFor="history">History</label></span>
              {canAssign && <Button size="sm" onClick={() => setAssigning(true)}><UserPlus aria-hidden className="h-4 w-4" /> Assign faculty</Button>}
            </div>} />
          <DataTable caption="Faculty assignments" columns={columns} rows={faculty.data} rowKey={(f) => f.id!} loading={faculty.isPending}
            error={faculty.isError ? errorInfo(faculty.error) : null} onRetry={() => void faculty.refetch()}
            empty={{ title: "No faculty assigned", description: canAssign ? "Assign teaching staff of this institution." : undefined }} />
        </Card>
      </div>
      {assigning && <AssignDialog offeringId={o.id!} hasFaculty={(faculty.data ?? []).some((f) => f.current)} onClose={() => setAssigning(false)} />}
      <StatusDialog open={!!ending} onOpenChange={(v) => !v && setEnding(null)} busy={end.isPending} error={problem} reasonRequired
        title={`End ${ending?.staffName}'s assignment?`} confirmLabel="End assignment" tone="danger"
        description="The assignment is kept in the history with today's date and your reason." onConfirm={(r) => end.mutate(r)} />
      <StatusDialog open={!!target} onOpenChange={(v) => !v && setTarget(null)} busy={status.isPending} error={problem} reasonRequired={target === "CANCELLED"}
        title={target === "CANCELLED" ? "Cancel this offering?" : `Mark ${statusLabel(target ?? "").toLowerCase()}?`} confirmLabel={target === "CANCELLED" ? "Cancel offering" : "Change status"}
        tone={target === "CANCELLED" ? "danger" : "primary"}
        description={target === "CANCELLED" ? "Current faculty assignments end with it (kept in the history). The subject can be offered again later." : "Recorded in the audit trail."}
        onConfirm={(r) => status.mutate(r)} />
    </>
  );
}

function AssignDialog({ offeringId, hasFaculty, onClose }: { offeringId: string; hasFaculty: boolean; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const [search, setSearch] = useState("");
  const [staff, setStaff] = useState("");
  const [primary, setPrimary] = useState(!hasFaculty);
  const [startsOn, setStartsOn] = useState("");
  const [endsOn, setEndsOn] = useState("");
  const [error, setError] = useState<string | null>(null);
  const candidates = useQuery({ queryKey: ["academics", "candidates", offeringId, search],
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/subject-offerings/{id}/faculty-candidates", { params: { path: { id: offeringId }, query: search ? { q: search } : {} } })) });
  const assign = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/academics/subject-offerings/{id}/faculty", { params: { path: { id: offeringId } }, headers: { "Idempotency-Key": key.get() },
      body: { staffProfileId: staff, primary, ...(startsOn ? { startsOn } : {}), ...(endsOn ? { endsOn } : {}) } })),
    onSuccess: () => { invalidate(); toast.show("success", "Faculty assigned"); onClose(); },
    onError: (e) => { key.rotate(); setError(errorInfo(e).message); },
  });
  const options = (candidates.data ?? []).filter((c) => !c.alreadyAssigned);
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="Assign faculty" description="Active teaching staff of this institution. A new primary replaces the current primary."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={assign.isPending} disabled={!staff} onClick={() => { setError(null); assign.mutate(); }}>Assign</Button></>}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {error && <Alert tone="danger" className="sm:col-span-2">{error}</Alert>}
        <div className="sm:col-span-2"><Field label="Find staff">{(p) => <Input {...p} type="search" value={search} maxLength={100} placeholder="Name, staff number or designation" onChange={(e) => setSearch(e.target.value)} />}</Field></div>
        <div className="sm:col-span-2"><Field label="Staff member" hint={candidates.isSuccess && options.length === 0 ? "No other eligible staff found." : undefined}>
          {(p) => <Select {...p} value={staff} onChange={(e) => setStaff(e.target.value)}><option value="">Choose…</option>
            {options.map((c) => <option key={c.staffProfileId} value={c.staffProfileId}>{c.name} ({c.staffNo}){c.designation ? ` · ${c.designation}` : ""} · {c.unitName}</option>)}</Select>}
        </Field></div>
        <Field label="Starts on (optional)" hint="Defaults to today.">{(p) => <Input {...p} type="date" value={startsOn} onChange={(e) => setStartsOn(e.target.value)} />}</Field>
        <Field label="Ends on (optional)">{(p) => <Input {...p} type="date" value={endsOn} min={startsOn || undefined} onChange={(e) => setEndsOn(e.target.value)} />}</Field>
        <div className="flex items-center gap-2 sm:col-span-2"><Switch id="primary" checked={primary} onCheckedChange={setPrimary} label="Primary faculty" /><label htmlFor="primary" className="text-sm">Primary faculty</label></div>
      </div>
    </Modal>
  );
}

export default OfferingDetailPage;
