import { unwrap, type StudentDetail, type StudentEnrolment } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, DataTable, Field, Input, Modal, Select, TextArea, useToast, type Column } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowRightLeft, Plus } from "lucide-react";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { Facts, StatusDialog, useIdempotencyKey } from "../academics/AcademicsShared";
import { formatDate } from "../academics/academicsModel";
import { useCourseOptions, useProgramOptions, useTermOptions, useYearOptions } from "../academics/useAcademicOptions";
import { availableChanges, CHANGE_LABELS, needsReason, titleCase, type EnrolmentChange, type StudentStatus } from "./studentModel";
import { useInvalidateStudents, useStudentOptions } from "./StudentShared";

/** Admission record, current enrolment, enrolment changes and the full academic history. */
export function AcademicTab({ detail }: { detail: StudentDetail }) {
  const api = useApi();
  const labels = useOrgLabels();
  const s = detail.student!;
  const a = detail.admission;
  const e = detail.currentEnrolment;
  const [dialog, setDialog] = useState<"admission" | "enrolment" | "confirm" | "cancel" | null>(null);
  const history = useQuery({ queryKey: ["students", "enrolments", s.id], queryFn: async () => unwrap(await api.GET("/api/v1/students/{studentId}/enrolments", { params: { path: { studentId: s.id! } } })) });
  const manage = !!detail.can?.manage && !!s.active;
  const changes = availableChanges(s.status as StudentStatus, !!e);
  const canAdmit = manage && (s.status === "PROSPECTIVE" || s.status === "ADMITTED") && (!a || a.status === "CANCELLED");
  const columns: Column<StudentEnrolment>[] = [
    { key: "change", header: "Change", cell: (r) => <span className="block min-w-0"><span className="block font-medium">{CHANGE_LABELS[r.changeType as EnrolmentChange]}</span>
      {r.reason && r.reason !== CHANGE_LABELS[r.changeType as EnrolmentChange] && <span className="block truncate text-xs text-muted">{r.reason}</span>}</span> },
    { key: "course", header: `${labels.singular("COURSE")} / ${labels.singular("BATCH").toLowerCase()}`, cell: (r) => <span className="block min-w-0">
      <span className="block truncate">{r.courseName}</span><span className="block truncate text-xs text-muted">{r.batchCode} · {r.academicYearName}</span></span> },
    { key: "term", header: labels.singular("TERM"), hideOnMobile: true, cell: (r) => <span className="tabular">{r.termNumber}</span> },
    { key: "period", header: "Period", hideOnMobile: true, cell: (r) => `${formatDate(r.enrolledOn)} – ${r.endedOn ? formatDate(r.endedOn) : "now"}` },
    { key: "status", header: "Status", cell: (r) => r.current ? <Badge tone={r.status === "SUSPENDED" ? "warning" : "success"}>Current · {titleCase(r.status)}</Badge> : <Badge>{titleCase(r.status)}</Badge> },
  ];
  return (
    <div className="space-y-6">
      <Card>
        <CardHeader title="Admission" description="Kept separate from the profile. Confirming an admission admits the student."
          actions={manage && a?.status === "PROVISIONAL" ? <div className="flex gap-2"><Button size="sm" onClick={() => setDialog("confirm")}>Confirm admission</Button>
            <Button size="sm" variant="secondary" onClick={() => setDialog("cancel")}>Cancel admission</Button></div>
            : manage && a?.status === "CONFIRMED" && !a.enrolled ? <Button size="sm" variant="secondary" onClick={() => setDialog("cancel")}>Cancel admission</Button>
            : canAdmit ? <Button size="sm" onClick={() => setDialog("admission")}><Plus aria-hidden className="h-4 w-4" /> Record admission</Button> : undefined} />
        <CardBody>
          {a ? <Facts items={[
            { label: "Admission number", value: <span className="inline-flex items-center gap-2"><code>{a.admissionNo}</code><Badge tone={a.status === "CONFIRMED" ? "success" : a.status === "CANCELLED" ? "danger" : "info"}>{titleCase(a.status)}</Badge></span> },
            { label: "Admission date", value: formatDate(a.admissionDate) }, { label: "Admission type", value: a.admissionTypeLabel },
            { label: "Admission category", value: detail.personalVisible ? (a.admissionCategoryLabel ?? "—") : "Restricted" },
            { label: "Entry academic year", value: a.entryAcademicYearName }, { label: labels.singular("PROGRAM"), value: a.programName },
            { label: labels.singular("COURSE"), value: a.courseName }, { label: labels.singular("DEPARTMENT"), value: a.departmentName },
            { label: `Entry ${labels.singular("TERM").toLowerCase()}`, value: a.entryTermNumber },
            ...(a.previousInstitutionName ? [{ label: "Previous institution", value: `${a.previousInstitutionName}${a.previousInstitutionDetails ? ` — ${a.previousInstitutionDetails}` : ""}` }] : []),
            ...(a.statusReason ? [{ label: "Cancellation reason", value: a.statusReason }] : []),
          ]} /> : <p className="text-sm text-muted">No admission recorded. The full admissions application and screening workflow is part of the later admissions module.</p>}
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Current enrolment" actions={manage && changes.length > 0 ? <Button size="sm" onClick={() => setDialog("enrolment")}>
          {changes[0] === "INITIAL" ? <><Plus aria-hidden className="h-4 w-4" /> Enrol in a {labels.singular("BATCH").toLowerCase()}</> : <><ArrowRightLeft aria-hidden className="h-4 w-4" /> Change enrolment</>}</Button> : undefined} />
        <CardBody>
          {e ? <Facts items={[
            { label: `${labels.singular("UNIVERSITY")} / ${labels.singular("COLLEGE").toLowerCase()}`, value: e.institutionName }, { label: labels.singular("CAMPUS"), value: e.campusName || "—" },
            { label: labels.singular("DEPARTMENT"), value: e.departmentName }, { label: labels.singular("PROGRAM"), value: e.programName },
            { label: labels.singular("COURSE"), value: e.courseName }, { label: labels.singular("BATCH"), value: `${e.batchName} (${e.batchCode})` },
            { label: "Academic year", value: e.academicYearName }, { label: labels.singular("TERM"), value: `${e.termNumber}${e.termName ? ` · ${e.termName}` : ""}` },
            { label: "Enrolled on", value: formatDate(e.enrolledOn) }, { label: "Status", value: titleCase(e.status) },
          ]} /> : <p className="text-sm text-muted">{s.status === "ADMITTED" ? `Admitted; enrol the student in a ${labels.singular("BATCH").toLowerCase()} of the admitted ${labels.singular("COURSE").toLowerCase()}.` : "Not enrolled."}</p>}
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Academic history" description="Every enrolment, progression, promotion and transfer. Earlier records are never overwritten." />
        <DataTable caption="Academic history" columns={columns} rows={history.data} rowKey={(r) => r.id!} loading={history.isPending}
          error={history.isError ? errorInfo(history.error) : null} onRetry={() => void history.refetch()}
          empty={{ title: "No enrolments yet", description: "The history starts with the initial enrolment." }} />
      </Card>
      {dialog === "admission" && <AdmissionDialog detail={detail} onClose={() => setDialog(null)} />}
      {dialog === "enrolment" && <EnrolmentDialog detail={detail} changes={changes} onClose={() => setDialog(null)} />}
      {(dialog === "confirm" || dialog === "cancel") && a && <AdmissionStatusDialog detail={detail} confirm={dialog === "confirm"} onClose={() => setDialog(null)} />}
    </div>
  );
}

function AdmissionDialog({ detail, onClose }: { detail: StudentDetail; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const labels = useOrgLabels();
  const invalidate = useInvalidateStudents();
  const key = useIdempotencyKey();
  const options = useStudentOptions();
  const s = detail.student!;
  const programs = useProgramOptions().filter((p) => p.status === "ACTIVE");
  const years = useYearOptions().filter((y) => y.status !== "COMPLETED");
  const [v, setV] = useState({ admissionNo: "", admissionDate: new Date().toISOString().slice(0, 10), admissionType: "REGULAR", admissionCategory: "", programId: "",
    courseId: "", entryAcademicYearId: "", entryTermNumber: "1", status: "CONFIRMED", previousInstitutionName: "", previousInstitutionDetails: "" });
  const courses = useCourseOptions(v.programId || undefined, !!v.programId).filter((c) => c.status === "ACTIVE");
  const course = courses.find((c) => c.id === v.courseId);
  const [problem, setProblem] = useState<string | null>(null);
  const set = (k: keyof typeof v, value: string) => setV((x) => ({ ...x, [k]: value }));
  const run = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/students/{studentId}/admissions", { params: { path: { studentId: s.id! } }, headers: { "Idempotency-Key": key.get() },
      body: { admissionNo: v.admissionNo.trim(), admissionDate: v.admissionDate, admissionType: v.admissionType, ...(v.admissionCategory ? { admissionCategory: v.admissionCategory } : {}),
        programId: v.programId, courseId: v.courseId, entryAcademicYearId: v.entryAcademicYearId, entryTermNumber: Number(v.entryTermNumber), status: v.status as "CONFIRMED",
        ...(v.previousInstitutionName ? { previousInstitutionName: v.previousInstitutionName } : {}), ...(v.previousInstitutionDetails ? { previousInstitutionDetails: v.previousInstitutionDetails } : {}) } })),
    onSuccess: () => { invalidate(); toast.show("success", "Admission recorded", v.status === "CONFIRMED" ? "The student is now admitted." : "Provisional until confirmed."); onClose(); },
    onError: (e) => { key.rotate(); setProblem(errorInfo(e).message); },
  });
  const ready = v.admissionNo.trim() && v.programId && v.courseId && v.entryAcademicYearId;
  return (
    <Modal open size="lg" onOpenChange={(o) => !o && onClose()} title="Record admission" description={`For ${s.fullName}. This is the admission record only; applications and screening come with the admissions module.`}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={run.isPending} disabled={!ready} onClick={() => { setProblem(null); run.mutate(); }}>Record admission</Button></>}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {problem && <Alert tone="danger" className="sm:col-span-2">{problem}</Alert>}
        <Field label="Admission number">{(f) => <Input {...f} value={v.admissionNo} maxLength={40} onChange={(e) => set("admissionNo", e.target.value)} />}</Field>
        <Field label="Admission date">{(f) => <Input {...f} type="date" value={v.admissionDate} onChange={(e) => set("admissionDate", e.target.value)} />}</Field>
        <Field label="Admission type">{(f) => <Select {...f} value={v.admissionType} onChange={(e) => { set("admissionType", e.target.value); if (e.target.value === "REGULAR") set("entryTermNumber", "1"); }}>
          {options.of("ADMISSION_TYPE").map((o) => <option key={o.code} value={o.code}>{o.label}</option>)}</Select>}</Field>
        {detail.personalVisible && <Field label="Admission category (optional)" hint="Personal information: visible only with permission.">
          {(f) => <Select {...f} value={v.admissionCategory} onChange={(e) => set("admissionCategory", e.target.value)}><option value="">Not recorded</option>
            {options.of("ADMISSION_CATEGORY").map((o) => <option key={o.code} value={o.code}>{o.label}</option>)}</Select>}</Field>}
        <Field label={labels.singular("PROGRAM")}>{(f) => <Select {...f} value={v.programId} onChange={(e) => setV((x) => ({ ...x, programId: e.target.value, courseId: "" }))}>
          <option value="">Choose…</option>{programs.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.ownerName}</option>)}</Select>}</Field>
        <Field label={labels.singular("COURSE")}>{(f) => <Select {...f} value={v.courseId} onChange={(e) => set("courseId", e.target.value)} disabled={!v.programId}>
          <option value="">Choose…</option>{courses.map((c) => <option key={c.id} value={c.id}>{c.name} · {c.departmentName}</option>)}</Select>}</Field>
        <Field label="Entry academic year">{(f) => <Select {...f} value={v.entryAcademicYearId} onChange={(e) => set("entryAcademicYearId", e.target.value)}>
          <option value="">Choose…</option>{years.map((y) => <option key={y.id} value={y.id}>{y.name}{y.ownerName ? ` · ${y.ownerName}` : ""}</option>)}</Select>}</Field>
        <Field label={`Entry ${labels.singular("TERM").toLowerCase()}`} hint={v.admissionType === "REGULAR" ? "Regular admissions start in term 1." : "Lateral entry and transfers may start later."}>
          {(f) => <Select {...f} value={v.entryTermNumber} disabled={v.admissionType === "REGULAR"} onChange={(e) => set("entryTermNumber", e.target.value)}>
            {Array.from({ length: course?.effectiveTerms ?? 8 }, (_, i) => <option key={i + 1} value={i + 1}>{labels.singular("TERM")} {i + 1}</option>)}</Select>}</Field>
        <Field label="Status">{(f) => <Select {...f} value={v.status} onChange={(e) => set("status", e.target.value)}><option value="CONFIRMED">Confirmed</option><option value="PROVISIONAL">Provisional</option></Select>}</Field>
        {v.admissionType !== "REGULAR" && <>
          <Field label={v.admissionType === "TRANSFER" ? "Previous institution" : "Previous institution (optional)"}>{(f) => <Input {...f} value={v.previousInstitutionName} maxLength={200} onChange={(e) => set("previousInstitutionName", e.target.value)} />}</Field>
          <Field label="Previous institution details (optional)">{(f) => <TextArea {...f} rows={2} value={v.previousInstitutionDetails} maxLength={500} onChange={(e) => set("previousInstitutionDetails", e.target.value)} />}</Field>
        </>}
      </div>
    </Modal>
  );
}

function AdmissionStatusDialog({ detail, confirm, onClose }: { detail: StudentDetail; confirm: boolean; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const invalidate = useInvalidateStudents();
  const a = detail.admission!;
  const [problem, setProblem] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: async (reason: string) => unwrap(await api.POST("/api/v1/students/{studentId}/admissions/{admissionId}/status", { params: { path: { studentId: detail.student!.id!, admissionId: a.id! } },
      body: { status: confirm ? "CONFIRMED" : "CANCELLED", version: a.version!, ...(reason ? { reason } : {}) } })),
    onSuccess: () => { invalidate(); toast.show("success", confirm ? "Admission confirmed" : "Admission cancelled"); onClose(); },
    onError: (e) => setProblem(errorInfo(e).message),
  });
  return <StatusDialog open onOpenChange={(o) => !o && onClose()} busy={run.isPending} error={problem} reasonRequired={!confirm} tone={confirm ? "primary" : "danger"}
    title={confirm ? `Confirm admission ${a.admissionNo}?` : `Cancel admission ${a.admissionNo}?`} confirmLabel={confirm ? "Confirm" : "Cancel admission"}
    description={confirm ? "The student becomes admitted and can be enrolled." : "An admitted student without an enrolment is cancelled with it. The record is kept."}
    onConfirm={(r) => run.mutate(r)} />;
}

function EnrolmentDialog({ detail, changes, onClose }: { detail: StudentDetail; changes: EnrolmentChange[]; onClose: () => void }) {
  const api = useApi();
  const toast = useToast();
  const labels = useOrgLabels();
  const { access } = useSession();
  const invalidate = useInvalidateStudents();
  const key = useIdempotencyKey();
  const s = detail.student!;
  const cur = detail.currentEnrolment;
  const [type, setType] = useState<EnrolmentChange>(changes[0]!);
  const courseId = type === "INITIAL" ? detail.admission?.courseId : type === "COURSE_TRANSFER" ? undefined : cur?.courseId;
  const [targetCourse, setTargetCourse] = useState("");
  const effectiveCourse = type === "COURSE_TRANSFER" ? targetCourse : courseId;
  const courses = useCourseOptions(undefined, type === "COURSE_TRANSFER").filter((c) => c.status === "ACTIVE" && c.id !== cur?.courseId);
  const batches = useQuery({ queryKey: ["academics", "options", "batches", effectiveCourse ?? ""], enabled: !!effectiveCourse && access.hasAnywhere("academics.read"),
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/batches", { params: { query: { size: 100, courseId: effectiveCourse! } } })).items ?? [] });
  const years = useYearOptions().filter((y) => y.status !== "COMPLETED");
  const [batchId, setBatchId] = useState(type === "PROGRESSION" || type === "PROMOTION" ? cur?.batchId ?? "" : "");
  const [yearId, setYearId] = useState("");
  const terms = useTermOptions(yearId || undefined, !!yearId).filter((t) => t.status !== "COMPLETED");
  const [termId, setTermId] = useState("");
  const [termNumber, setTermNumber] = useState(type === "INITIAL" ? String(detail.admission?.entryTermNumber ?? 1) : String((cur?.termNumber ?? 0) + 1));
  const [effectiveOn, setEffectiveOn] = useState(new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const pick = (t: EnrolmentChange) => {
    setType(t); setTargetCourse(""); setBatchId(t === "PROGRESSION" || t === "PROMOTION" ? cur?.batchId ?? "" : ""); setYearId(""); setTermId("");
    setTermNumber(t === "PROGRESSION" || t === "PROMOTION" ? String((cur?.termNumber ?? 0) + 1) : String(cur?.termNumber ?? detail.admission?.entryTermNumber ?? 1));
  };
  const run = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/students/{studentId}/enrolments", { params: { path: { studentId: s.id! } }, headers: { "Idempotency-Key": key.get() },
      body: { type, batchId, termNumber: Number(termNumber), effectiveOn, ...(yearId ? { academicYearId: yearId } : {}), ...(termId ? { termId } : {}),
        ...(reason.trim() ? { reason: reason.trim() } : {}) } })),
    onSuccess: () => { invalidate(); toast.show("success", CHANGE_LABELS[type], "The previous enrolment is kept in the academic history."); onClose(); },
    onError: (e) => { key.rotate(); setProblem(errorInfo(e).message); },
  });
  const ready = !!batchId && !!termNumber && (!needsReason(type) || reason.trim()) && (type !== "PROMOTION" || !!yearId);
  return (
    <Modal open size="lg" onOpenChange={(o) => !o && onClose()} title={type === "INITIAL" ? `Enrol ${s.fullName}` : `Change enrolment of ${s.fullName}`}
      description={cur ? `Currently ${cur.courseName}, ${cur.batchCode}, ${labels.singular("TERM").toLowerCase()} ${cur.termNumber} (${cur.academicYearName}).` : `Admitted to ${detail.admission?.courseName}.`}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button loading={run.isPending} disabled={!ready} onClick={() => { setProblem(null); run.mutate(); }}>{CHANGE_LABELS[type]}</Button></>}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {problem && <Alert tone="danger" className="sm:col-span-2">{problem}</Alert>}
        {changes.length > 1 && <div className="sm:col-span-2"><Field label="Change">{(f) => <Select {...f} value={type} onChange={(e) => pick(e.target.value as EnrolmentChange)}>
          {changes.map((c) => <option key={c} value={c}>{CHANGE_LABELS[c]}</option>)}</Select>}</Field></div>}
        {type === "COURSE_TRANSFER" && <div className="sm:col-span-2"><Field label={`New ${labels.singular("COURSE").toLowerCase()}`} hint="Needs permission in both departments; stays within the student's institution.">
          {(f) => <Select {...f} value={targetCourse} onChange={(e) => { setTargetCourse(e.target.value); setBatchId(""); }}><option value="">Choose…</option>
            {courses.map((c) => <option key={c.id} value={c.id}>{c.name} · {c.programName}</option>)}</Select>}</Field></div>}
        <Field label={labels.singular("BATCH")} hint={type === "PROGRESSION" || type === "PROMOTION" ? "Stays the same." : undefined}>
          {(f) => <Select {...f} value={batchId} disabled={type === "PROGRESSION" || type === "PROMOTION"} onChange={(e) => setBatchId(e.target.value)}><option value="">Choose…</option>
            {(batches.data ?? []).filter((b) => b.status === "ACTIVE" || b.status === "PLANNED" || b.id === batchId).map((b) => <option key={b.id} value={b.id}>{b.name} ({b.code}){b.capacity ? ` · capacity ${b.capacity}` : ""}</option>)}</Select>}
        </Field>
        <Field label={`${labels.singular("COURSE")} ${labels.singular("TERM").toLowerCase()}`}>{(f) => <Input {...f} type="number" min={1} max={40} value={termNumber} onChange={(e) => setTermNumber(e.target.value)} />}</Field>
        <Field label={type === "PROMOTION" ? "Next academic year" : "Academic year (optional)"} hint={type === "PROMOTION" ? undefined : `Defaults to the ${type === "INITIAL" ? labels.singular("BATCH").toLowerCase() + "'s" : "current"} year.`}>
          {(f) => <Select {...f} value={yearId} onChange={(e) => { setYearId(e.target.value); setTermId(""); }}><option value="">{type === "PROMOTION" ? "Choose…" : "Default"}</option>
            {years.map((y) => <option key={y.id} value={y.id}>{y.name}</option>)}</Select>}
        </Field>
        <Field label={`Calendar ${labels.singular("TERM").toLowerCase()} (optional)`}>{(f) => <Select {...f} value={termId} disabled={!yearId} onChange={(e) => setTermId(e.target.value)}>
          <option value="">Not set</option>{terms.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}</Select>}</Field>
        <Field label="Effective date">{(f) => <Input {...f} type="date" value={effectiveOn} onChange={(e) => setEffectiveOn(e.target.value)} />}</Field>
        <Field label={needsReason(type) ? "Reason" : "Reason (optional)"}>{(f) => <TextArea {...f} rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
      </div>
    </Modal>
  );
}
