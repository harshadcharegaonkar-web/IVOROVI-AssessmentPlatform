import { unwrap, type StudentDetail } from "@ivorovi/api-client";
import { Alert, Breadcrumbs, Button, ButtonLink, ConfirmDialog, Field, Input, Menu, MenuItem, Modal, PageHeader, Skeleton, TabPanel, Tabs, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { MoreHorizontal, Pencil } from "lucide-react";
import { useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { DetailError, StatusDialog } from "../academics/AcademicsShared";
import { AcademicTab } from "./AcademicTab";
import { DocumentsTab } from "./DocumentsTab";
import { GuardiansTab } from "./GuardiansTab";
import { manualTransitions, statusText, transitionVerb, type StudentStatus } from "./studentModel";
import { HistoryTab, OverviewTab, PersonalTab } from "./StudentProfileTabs";
import { StudentAvatar, StudentStatusBadge, useInvalidateStudents } from "./StudentShared";

type Dialog = { kind: "status"; to: StudentStatus } | { kind: "active" } | { kind: "account" } | { kind: "delete" } | null;

/** Student profile: organized into tabs so the profile is not overcrowded. */
export function StudentProfilePage() {
  const { id = "" } = useParams();
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "overview";
  const [dialog, setDialog] = useState<Dialog>(null);
  const q = useQuery({ queryKey: ["students", "detail", id], queryFn: async () => unwrap(await api.GET("/api/v1/students/{id}", { params: { path: { id } } })) });
  if (q.isPending) return <><Skeleton className="mb-4 h-8 w-72" /><Skeleton className="h-64 w-full" /></>;
  if (q.isError) return <DetailError error={q.error} what="Student" onRetry={() => void q.refetch()} />;
  const d = q.data;
  const s = d.student!;
  const can = d.can ?? {};
  const actions = manualTransitions(s.status as StudentStatus);
  const tabs = [
    { value: "overview", label: "Overview" },
    { value: "personal", label: "Personal information" },
    { value: "academic", label: "Academic" },
    ...(d.personalVisible ? [{ value: "guardians", label: "Guardians", count: d.guardianCount }] : []),
    ...(can.readDocuments ? [{ value: "documents", label: "Documents", count: d.documentCount }] : []),
    { value: "history", label: "History" },
  ];
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Students", to: "/students" }, { label: "Student list", to: "/students/list" }, { label: s.fullName ?? "" }]} /></div>
      <div className="mb-2 flex items-center gap-3">
        <StudentAvatar name={s.fullName ?? ""} size="lg" />
        <div className="min-w-0 flex-1">
          <PageHeader title={s.fullName ?? ""} description={<span className="inline-flex flex-wrap items-center gap-2"><code>{s.studentNo}</code><StudentStatusBadge status={s.status} />
            {!s.active && <span className="text-sm text-danger">Profile deactivated</span>}</span>}
            actions={can.manage ? <>
              {s.active && <ButtonLink to={`/students/${s.id}/edit`}><Pencil aria-hidden className="h-4 w-4" /> Edit</ButtonLink>}
              <Menu trigger={<Button variant="secondary" aria-label="More actions"><MoreHorizontal aria-hidden className="h-4 w-4" /> Actions</Button>}>
                {s.active && actions.map((to) => <MenuItem key={to} tone={to === "WITHDRAWN" || to === "CANCELLED" ? "danger" : undefined}
                  onSelect={() => setDialog({ kind: "status", to })}>{transitionVerb(s.status as StudentStatus, to)}…</MenuItem>)}
                {can.manageAccount && !d.account && s.active && <MenuItem onSelect={() => setDialog({ kind: "account" })}>Create sign-in account…</MenuItem>}
                <MenuItem onSelect={() => setDialog({ kind: "active" })}>{s.active ? "Deactivate profile…" : "Activate profile…"}</MenuItem>
                {(s.status === "PROSPECTIVE" || s.status === "CANCELLED") && <MenuItem tone="danger" onSelect={() => setDialog({ kind: "delete" })}>Delete (created in error)…</MenuItem>}
              </Menu>
            </> : undefined} />
        </div>
      </div>
      <Tabs label="Student sections" value={tab} onValueChange={(v) => setParams(v === "overview" ? {} : { tab: v }, { replace: true })} tabs={tabs}>
        <TabPanel value="overview"><OverviewTab detail={d} onCreateAccount={() => setDialog({ kind: "account" })} /></TabPanel>
        <TabPanel value="personal"><PersonalTab detail={d} /></TabPanel>
        <TabPanel value="academic"><AcademicTab detail={d} /></TabPanel>
        {d.personalVisible && <TabPanel value="guardians"><GuardiansTab detail={d} /></TabPanel>}
        {can.readDocuments && <TabPanel value="documents"><DocumentsTab detail={d} /></TabPanel>}
        <TabPanel value="history"><HistoryTab studentId={s.id!} /></TabPanel>
      </Tabs>
      {dialog?.kind === "status" && <LifecycleDialog detail={d} to={dialog.to} onClose={() => setDialog(null)} />}
      {dialog?.kind === "active" && <ActiveDialog detail={d} onClose={() => setDialog(null)} />}
      {dialog?.kind === "account" && <AccountDialog detail={d} onClose={() => setDialog(null)} />}
      {dialog?.kind === "delete" && <DeleteDialog detail={d} onClose={() => setDialog(null)} />}
    </>
  );
}

function LifecycleDialog({ detail, to, onClose }: { detail: StudentDetail; to: StudentStatus; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const s = detail.student!;
  const [effectiveOn, setEffectiveOn] = useState(new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/students/{id}/status", { params: { path: { id: s.id! } },
      body: { status: to, effectiveOn, reason: reason.trim(), version: detail.version! } })),
    onSuccess: () => { invalidate(); toast.show("success", `Status: ${statusText(to)}`, s.fullName); onClose(); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  const effect: Partial<Record<StudentStatus, string>> = {
    SUSPENDED: "The current enrolment is marked suspended and kept.", WITHDRAWN: "The current enrolment is closed; academic history is kept.",
    GRADUATED: "The current enrolment is closed as completed.", CANCELLED: "The open admission is cancelled.", ACTIVE: "The current enrolment becomes active.",
  };
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`${transitionVerb(s.status as StudentStatus, to)}: ${s.fullName}`}
      description={`${statusText(s.status)} → ${statusText(to)}. ${effect[to] ?? ""} The change is recorded with your reason.`}
      footer={<><Button variant="secondary" onClick={onClose} disabled={run.isPending}>Cancel</Button>
        <Button variant={to === "WITHDRAWN" || to === "CANCELLED" ? "danger" : "primary"} loading={run.isPending}
          onClick={() => { setTouched(true); if (reason.trim()) { setProblem(null); run.mutate(); } }}>{transitionVerb(s.status as StudentStatus, to)}</Button></>}>
      <div className="space-y-4">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Field label="Effective date">{(f) => <Input {...f} type="date" value={effectiveOn} onChange={(e) => setEffectiveOn(e.target.value)} />}</Field>
        <Field label="Reason" error={touched && !reason.trim() ? "Give a reason." : undefined} hint="Recorded in the lifecycle history and the audit trail.">
          {(f) => <TextArea {...f} rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
        </Field>
      </div>
    </Modal>
  );
}

function ActiveDialog({ detail, onClose }: { detail: StudentDetail; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const s = detail.student!;
  const [problem, setProblem] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: async (reason: string) => unwrap(await api.POST("/api/v1/students/{id}/active", { params: { path: { id: s.id! } },
      body: { active: !s.active, reason, version: detail.version! } })),
    onSuccess: () => { invalidate(); toast.show("success", s.active ? "Profile deactivated" : "Profile activated"); onClose(); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  return <StatusDialog open onOpenChange={(o) => !o && onClose()} busy={run.isPending} error={problem} reasonRequired tone={s.active ? "danger" : "primary"}
    title={s.active ? `Deactivate ${s.fullName}'s profile?` : `Activate ${s.fullName}'s profile?`} confirmLabel={s.active ? "Deactivate" : "Activate"}
    description={s.active ? "The profile and its history are kept but cannot be changed until it is activated again. The lifecycle status does not change." : "The profile can be changed again."}
    onConfirm={(r) => run.mutate(r)} />;
}

function AccountDialog({ detail, onClose }: { detail: StudentDetail; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const s = detail.student!;
  const [email, setEmail] = useState(detail.institutionalEmail ?? detail.personal?.personalEmail ?? "");
  const [problem, setProblem] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/students/{id}/account", { params: { path: { id: s.id! } }, body: { email: email.trim() } })),
    onSuccess: () => { invalidate(); toast.show("success", "Invitation sent", "The student sets a password from the invitation email."); onClose(); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Create a sign-in account for ${s.fullName}`}
      description="Accounts are never created automatically. This account gets the Student role only and can never be given another role."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={run.isPending} disabled={!email.trim()} onClick={() => { setProblem(null); run.mutate(); }}>Create and send invitation</Button></>}>
      <div className="space-y-4">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Field label="Email address for the invitation" hint="Defaults to the institutional email.">
          {(f) => <Input {...f} type="email" value={email} maxLength={320} onChange={(e) => setEmail(e.target.value)} />}
        </Field>
      </div>
    </Modal>
  );
}

function DeleteDialog({ detail, onClose }: { detail: StudentDetail; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const navigate = useNavigate();
  const invalidate = useInvalidateStudents();
  const s = detail.student!;
  const run = useMutation({
    mutationFn: async () => unwrap(await api.DELETE("/api/v1/students/{id}", { params: { path: { id: s.id! }, query: { version: detail.version! } } })),
    onSuccess: () => { invalidate(); toast.show("success", "Profile deleted"); void navigate("/students/list"); },
    onError: (e) => { toast.show("danger", "Could not delete", errorInfo(e).message); onClose(); },
  });
  return <ConfirmDialog open onOpenChange={(o) => !o && onClose()} title={`Delete ${s.fullName}?`} tone="danger" confirmLabel="Delete" busy={run.isPending}
    onConfirm={() => run.mutate()}
    description="Only for profiles created in error. Students with admissions, enrolments, documents or a sign-in account are never deleted; deactivate them instead." />;
}

export default StudentProfilePage;
