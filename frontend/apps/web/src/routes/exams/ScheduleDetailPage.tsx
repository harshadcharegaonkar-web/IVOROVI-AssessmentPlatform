import { unwrap, type CandidateIncident, type EnrolmentUploadResult, type ExamEnrolment, type ExamResultRow, type InvigilationCandidate } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, DataTable, Field, Input, Modal, PageHeader, Select, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";

/** One scheduled examination: details, enrolment by upload, invitations, extra time, session codes, cancellation. */
export function ScheduleDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const { access } = useSession();
  const manage = access.hasAnywhere("exam.schedule.manage");
  const read = access.hasAnywhere("exam.schedule.read");
  const path = { params: { path: { id } } };
  const s = useQuery({ queryKey: ["exam-schedule", id], queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules/{id}", path)) });
  const e = useQuery({ queryKey: ["exam-enrolments", id], enabled: read, queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules/{id}/enrolments", path)) });
  const refresh = () => { for (const k of ["exam-schedule", "exam-enrolments", "exam-monitor"]) void qc.invalidateQueries({ queryKey: [k, id] }); };
  const [csv, setCsv] = useState("");
  const [preview, setPreview] = useState<EnrolmentUploadResult | null>(null);
  const [extraFor, setExtraFor] = useState<ExamEnrolment | null>(null);
  const [codes, setCodes] = useState<{ code?: string; hint?: string }[] | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const policy = useMutation({
    mutationFn: async (secondPerson: boolean) => unwrap(await api.PUT("/api/v1/exam/schedules/{id}/extra-time-policy", { ...path, body: { secondPerson } })),
    onSuccess: refresh,
  });
  const upload = useMutation({
    mutationFn: async (apply: boolean) => unwrap(await api.POST("/api/v1/exam/schedules/{id}/enrolments/upload", { ...path, body: { csv, apply } })),
    onSuccess: (r) => { setPreview(r); if (r.applied) { setCsv(""); refresh(); } },
  });
  const invite = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/exam/schedules/{id}/invitations", path)), onSuccess: refresh });
  const issue = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/schedules/{id}/codes", { ...path, body: { count: Math.max(1, e.data?.filter((x) => x.status === "ACTIVE").length ?? 1) } })),
    onSuccess: (c) => { setCodes(c); refresh(); },
  });
  if (s.isError) return <><PageHeader title="Schedule" /><Alert tone="danger">{errorInfo(s.error).message}</Alert></>;
  const x = s.data;
  if (!x) return <PageHeader title="Schedule" description="Loading…" />;
  const open = x.status === "SCHEDULED";
  const columns: Column<ExamEnrolment>[] = [
    { key: "e", header: "Candidate", cell: (r) => <span className="flex flex-col"><span>{r.displayName ?? r.email}</span><span className="text-xs text-muted">{r.email}</span></span> },
    { key: "l", header: "Account", cell: (r) => r.linked ? <Badge tone="success">Linked</Badge> : <Badge tone="neutral">Not signed in yet</Badge>, hideOnMobile: true },
    { key: "i", header: "Invitation", cell: (r) => ({ SENT: `Sent ${formatDateTime(r.invitedAt)}`, FAILED: "Failed — will retry", NOT_SENT: "Not sent" }[r.invitationStatus ?? ""] ?? r.invitationStatus) },
    { key: "x", header: "Extra time", cell: (r) => r.extraMinutes ? `${r.extraMinutes} min (${r.extraReason})` : "—" },
    { key: "c", header: "Checked in", cell: (r) => r.checkedIn ? "Yes" : "No", hideOnMobile: true },
    ...(manage && open ? [{ key: "a", header: "", cell: (r: ExamEnrolment) => r.status === "ACTIVE" && <Button variant="ghost" onClick={() => setExtraFor(r)}>Extra time</Button> }] : []),
  ];
  return (
    <>
      <PageHeader title={x.title ?? "Schedule"} description={<span className="flex flex-wrap items-center gap-2">{x.assessmentCode} · version {x.versionNo} · {x.orgUnitName ?? "Whole organization"}
        <Badge tone={open ? "success" : "neutral"}>{open ? "Scheduled" : "Cancelled"}</Badge></span>}
        actions={manage && open && <Button variant="secondary" onClick={() => setCancelling(true)}>Cancel examination</Button>} />
      <div className="space-y-4">
        <Card>
          <CardHeader title="Examination" />
          <CardBody>
            <dl className="grid gap-3 text-sm sm:grid-cols-3">
              <div><dt className="text-muted">Window</dt><dd>{formatDateTime(x.opensAt)} – {formatDateTime(x.closesAt)}</dd></div>
              <div><dt className="text-muted">Duration</dt><dd>{x.durationMinutes} minutes{x.lateEntryUntil ? ` · late entry until ${formatDateTime(x.lateEntryUntil)}` : ""}</dd></div>
              <div><dt className="text-muted">Portal</dt><dd>{x.portalName}{x.portalUrl ? <span className="block break-all text-xs text-muted">{x.portalUrl}</span> : null}</dd></div>
              <div><dt className="text-muted">Candidates</dt><dd>{x.enrolled} enrolled · {x.linked} signed in · {x.invited} invited · {x.checkedIn} checked in</dd></div>
              <div><dt className="text-muted">Calculator</dt><dd>{x.calculator ? "Allowed" : "Not allowed"}</dd></div>
              <div><dt className="text-muted">Scheduled by</dt><dd>{x.createdByName ?? "—"}</dd></div>
              <div><dt className="text-muted">Extra time during the examination</dt><dd className="flex flex-wrap items-center gap-2">
                {x.extraTimeSecondPerson ? "A second person approves each grant" : "Granted directly by designated controllers and invigilators"}
                {manage && open && <Button variant="ghost" loading={policy.isPending} onClick={() => policy.mutate(!x.extraTimeSecondPerson)}>
                  {x.extraTimeSecondPerson ? "Allow direct grants" : "Require a second person"}</Button>}</dd></div>
            </dl>
            {policy.isError && <div className="mt-3"><Alert tone="danger">{errorInfo(policy.error).message}</Alert></div>}
            {x.instructions && <p className="mt-3 whitespace-pre-line text-sm"><span className="font-medium">Instructions: </span>{x.instructions}</p>}
          </CardBody>
        </Card>

        {manage && open && (
          <Card>
            <CardHeader title="Enrol candidates" description="One candidate per line: email, name, extra minutes, reason for extra time. A header line is optional. Uploading the same list again adds nothing twice." />
            <CardBody>
              <div className="space-y-3">
                <Field label="Candidates (CSV)">{(p) => <TextArea {...p} rows={5} value={csv} placeholder={"email,name,extra_minutes,extra_reason\nriya@example.org,Riya Sharma\narjun@example.org,Arjun K,15,Scribe approved"}
                  onChange={(ev) => { setCsv(ev.target.value); setPreview(null); }} />}</Field>
                <Field label="Or choose a CSV file">{(p) => <Input {...p} type="file" accept=".csv,text/csv" onChange={(ev) => { const f = ev.target.files?.[0]; if (f) void f.text().then((t) => { setCsv(t); setPreview(null); }); }} />}</Field>
                {upload.isError && <Alert tone="danger">{errorInfo(upload.error).message}</Alert>}
                {preview && (
                  <Alert tone={preview.errors?.length ? "warning" : "success"}>
                    {preview.applied ? `Enrolled ${preview.created} new candidate(s); ${preview.alreadyEnrolled} were already enrolled.` : `${preview.valid} of ${preview.rows} rows are ready to enrol.`}
                    {(preview.errors ?? []).length > 0 && <ul className="mt-1 list-disc pl-5">{preview.errors!.map((r) => <li key={r.row}>Line {r.row}: {r.message}</li>)}</ul>}
                  </Alert>)}
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" disabled={!csv.trim()} loading={upload.isPending && !upload.variables} onClick={() => upload.mutate(false)}>Check file</Button>
                  <Button variant="primary" disabled={!csv.trim() || !preview || preview.applied || preview.valid === 0} loading={upload.isPending && !!upload.variables} onClick={() => upload.mutate(true)}>Enrol {preview && !preview.applied ? preview.valid : ""} candidates</Button>
                </div>
              </div>
            </CardBody>
          </Card>)}

        {access.hasAnywhere("exam.invigilate") && open && <MonitorCard scheduleId={id} />}

        {read && <Card>
          <CardHeader title={`Enrolled candidates (${e.data?.filter((r) => r.status === "ACTIVE").length ?? 0})`}
            actions={manage && open && <div className="flex flex-wrap gap-2">
              <Button variant="secondary" loading={invite.isPending} onClick={() => invite.mutate()}>Send invitations</Button>
              {access.hasAnywhere("exam.session.manage") && <Button variant="secondary" loading={issue.isPending} onClick={() => issue.mutate()}>Issue session codes</Button>}
            </div>} />
          {invite.data && <div className="px-5 pt-3"><Alert tone={invite.data.failed ? "warning" : "success"}>Invitations sent: {invite.data.sent}{invite.data.failed ? `, failed: ${invite.data.failed}` : ""}.</Alert></div>}
          {invite.isError && <div className="px-5 pt-3"><Alert tone="danger">{errorInfo(invite.error).message}</Alert></div>}
          <DataTable caption="Enrolled candidates" columns={columns} rows={e.data} rowKey={(r) => r.id!} loading={e.isPending}
            error={e.isError ? errorInfo(e.error) : null} empty={{ title: "Nobody enrolled yet", description: "Upload a list of candidates above." }} />
        </Card>}
        <ResultsCard scheduleId={id} />
      </div>
      {codes && (
        <Modal open onOpenChange={(o) => !o && setCodes(null)} title="Session codes" description="Shown only now. Hand one code to each candidate at the examination; each works once."
          footer={<Button variant="primary" onClick={() => setCodes(null)}>Done</Button>}>
          <ol className="grid grid-cols-2 gap-2 font-mono text-sm" aria-label="Session codes">{codes.map((c) => <li key={c.code}>{c.code}</li>)}</ol>
        </Modal>)}
      {extraFor && <ExtraTimeDialog scheduleId={id} enrolment={extraFor} onClose={() => setExtraFor(null)} onDone={() => { setExtraFor(null); refresh(); }} />}
      {cancelling && <CancelDialog scheduleId={id} onClose={() => setCancelling(false)} onDone={() => { setCancelling(false); refresh(); }} />}
    </>
  );
}

function ExtraTimeDialog({ scheduleId, enrolment, onClose, onDone }: { scheduleId: string; enrolment: ExamEnrolment; onClose: () => void; onDone: () => void }) {
  const api = useApi();
  const [minutes, setMinutes] = useState(String(enrolment.extraMinutes ?? 0));
  const [reason, setReason] = useState(enrolment.extraReason ?? "");
  const save = useMutation({
    mutationFn: async () => unwrap(await api.PUT("/api/v1/exam/schedules/{id}/enrolments/{enrolmentId}/extra-time", {
      params: { path: { id: scheduleId, enrolmentId: enrolment.id! } }, body: { minutes: Number(minutes), reason: reason || undefined, version: enrolment.version! } })),
    onSuccess: onDone,
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Extra time for ${enrolment.displayName ?? enrolment.email}`} description="Added to the examination's duration, never beyond the window. Recorded with its reason."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} onClick={() => save.mutate()}>Save</Button></>}>
      <div className="space-y-4">
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        <Field label="Extra minutes">{(p) => <Input {...p} type="number" min={0} max={600} value={minutes} onChange={(ev) => setMinutes(ev.target.value)} />}</Field>
        <Field label="Reason">{(p) => <Input {...p} value={reason} maxLength={500} onChange={(ev) => setReason(ev.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

function CancelDialog({ scheduleId, onClose, onDone }: { scheduleId: string; onClose: () => void; onDone: () => void }) {
  const api = useApi();
  const [reason, setReason] = useState("");
  const save = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/exam/schedules/{id}/cancel", { params: { path: { id: scheduleId } }, body: { reason } })), onSuccess: onDone });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title="Cancel this examination?" description="Candidates can no longer start it and its session codes stop working. This cannot be undone."
      footer={<><Button variant="secondary" onClick={onClose}>Keep it</Button><Button variant="primary" disabled={!reason.trim()} loading={save.isPending} onClick={() => save.mutate()}>Cancel examination</Button></>}>
      {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
      <Field label="Reason">{(p) => <TextArea {...p} rows={2} maxLength={500} value={reason} onChange={(ev) => setReason(ev.target.value)} />}</Field>
    </Modal>
  );
}

/** Attempts, provisional and published scores, and the review → independent approval → publication workflow. */
function ResultsCard({ scheduleId }: { scheduleId: string }) {
  const api = useApi();
  const qc = useQueryClient();
  const { access } = useSession();
  const [reviewing, setReviewing] = useState(false);
  const [comment, setComment] = useState("");
  const r = useQuery({
    queryKey: ["exam-results", scheduleId], enabled: access.hasAnywhere("exam.results.read"), retry: false, refetchInterval: 15_000,
    queryFn: async () => unwrap(await api.GET("/api/v1/exam/results/{scheduleId}", { params: { path: { scheduleId } } })),
  });
  const review = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/results/{scheduleId}/reviews", { params: { path: { scheduleId } }, body: { comment: comment || undefined } })),
    onSuccess: (x) => { qc.setQueryData(["exam-results", scheduleId], x); setReviewing(false); setComment(""); },
  });
  if (!access.hasAnywhere("exam.results.read")) return null;
  if (r.isError) return <Card><CardHeader title="Results" description="No candidate has started this examination yet." /></Card>;
  const x = r.data;
  if (!x) return null;
  const pending = (x.reviews ?? []).find((v) => v.status === "PENDING_APPROVAL");
  const columns: Column<ExamResultRow>[] = [
    { key: "c", header: "Candidate", cell: (row) => <span className="flex flex-col"><span>{row.candidateName ?? row.candidateEmail}</span><span className="text-xs text-muted">{row.candidateEmail}</span></span> },
    { key: "a", header: "Attempt", cell: (row) => ({ IN_PROGRESS: "In progress", SUBMITTED: "Submitted", AUTO_SUBMITTED: "Submitted at time-up" }[row.attemptStatus ?? ""] ?? row.attemptStatus) },
    { key: "s", header: "Score", cell: (row) => row.awarded == null ? "Not scored yet" : `${row.awarded} / ${row.maxMarks}` },
    { key: "o", header: "Correct · wrong · blank", cell: (row) => row.correct == null ? "—" : `${row.correct} · ${row.incorrect} · ${row.unanswered}`, hideOnMobile: true },
    { key: "p", header: "Status", cell: (row) => row.scoreStatus === "RELEASED" ? <Badge tone="success">Published</Badge> : row.scoreStatus === "PROVISIONAL" ? <Badge tone="warning">Provisional</Badge> : <Badge tone="neutral">—</Badge> },
  ];
  return (
    <Card>
      <CardHeader title="Results" description={`${x.provisional} provisional · ${x.released} published · ${x.awaitingScore} awaiting scoring · ${x.inProgress} in progress`}
        actions={x.canReview && <Button variant="primary" onClick={() => setReviewing(true)}>Review results</Button>} />
      {pending && <div className="px-5 pt-3"><Alert tone="info">Reviewed by {pending.reviewerName}: {pending.attempts} result(s) wait for approval by another person.{" "}
        <Link to="/approvals" className="underline">Open approvals</Link></Alert></div>}
      <DataTable caption="Results" columns={columns} rows={x.rows} rowKey={(row) => row.attemptId!} empty={{ title: "No attempts", description: "" }} />
      {(x.reviews ?? []).length > 0 && (
        <CardBody>
          <ul className="space-y-1 text-sm">{x.reviews!.map((v) => (
            <li key={v.id}>{formatDateTime(v.createdAt)}: {v.attempts} result(s) reviewed by {v.reviewerName}
              {v.status === "PUBLISHED" ? `, approved by ${v.approverName} and published ${formatDateTime(v.decidedAt)}` : v.status === "PENDING_APPROVAL" ? ", waiting for approval" : ` — ${v.status?.toLowerCase()}`}
              {v.comment ? ` (“${v.comment}”)` : ""}</li>))}</ul>
        </CardBody>)}
      {reviewing && (
        <Modal open onOpenChange={(o) => !o && setReviewing(false)} title="Review results"
          description={`You confirm the ${x.provisional} provisional result(s). Another person with approval rights must approve before candidates see them.`}
          footer={<><Button variant="secondary" onClick={() => setReviewing(false)}>Cancel</Button><Button variant="primary" loading={review.isPending} onClick={() => review.mutate()}>Request publication</Button></>}>
          {review.isError && <Alert tone="danger">{errorInfo(review.error).message}</Alert>}
          <Field label="Comment (optional)">{(p) => <TextArea {...p} rows={2} maxLength={2000} value={comment} onChange={(ev) => setComment(ev.target.value)} />}</Field>
        </Modal>)}
    </Card>
  );
}

const STATE: Record<string, { label: string; tone: "neutral" | "info" | "warning" | "success" }> = {
  NOT_LINKED: { label: "Not signed in", tone: "neutral" }, NOT_CHECKED_IN: { label: "Not checked in", tone: "neutral" },
  CHECKED_IN: { label: "Checked in", tone: "info" }, IN_PROGRESS: { label: "Writing", tone: "warning" },
  SUBMITTED: { label: "Submitted", tone: "success" }, AUTO_SUBMITTED: { label: "Submitted at time-up", tone: "success" },
  TERMINATED: { label: "Ended by staff", tone: "warning" },
};
const CATEGORIES: [string, string][] = [["IDENTITY", "Identity check"], ["SUSPECTED_MALPRACTICE", "Suspected malpractice"], ["TECHNICAL", "Technical problem"],
  ["DISRUPTION", "Disruption in the room"], ["LATE_ARRIVAL", "Late arrival"], ["OTHER", "Other"]];
const minutesLeft = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;

/**
 * Live invigilation (exam.invigilate): every enrolled candidate's state, connection and progress, refreshed every 10 seconds;
 * never answers or scores. Incidents are recorded here and are permanent (a correction is a new incident).
 */
function MonitorCard({ scheduleId }: { scheduleId: string }) {
  const api = useApi();
  const qc = useQueryClient();
  const path = { params: { path: { id: scheduleId } } };
  const [incidentFor, setIncidentFor] = useState<InvigilationCandidate | null>(null);
  const [extendFor, setExtendFor] = useState<InvigilationCandidate | null>(null);
  const [endFor, setEndFor] = useState<InvigilationCandidate | null>(null);
  const [requested, setRequested] = useState<string | null>(null);
  const [historyFor, setHistoryFor] = useState<InvigilationCandidate | null>(null);
  const refreshMonitor = () => void qc.invalidateQueries({ queryKey: ["exam-monitor", scheduleId] });
  const m = useQuery({ queryKey: ["exam-monitor", scheduleId], refetchInterval: 10_000, retry: false,
    queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules/{id}/monitor", path)) });
  const inc = useQuery({ queryKey: ["exam-candidate-incidents", scheduleId], refetchInterval: 30_000, retry: false,
    queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules/{id}/candidate-incidents", path)) });
  if (m.isError) return <Card><CardHeader title="Live monitoring" /><CardBody><Alert tone="danger">{errorInfo(m.error).message}</Alert></CardBody></Card>;
  const x = m.data;
  const c = x?.counts ?? {};
  const columns: Column<InvigilationCandidate>[] = [
    { key: "n", header: "Candidate", cell: (r) => <span className="flex flex-col"><span>{r.displayName ?? r.email}</span><span className="text-xs text-muted">{r.email}</span></span> },
    { key: "s", header: "State", cell: (r) => { const st = STATE[r.state ?? ""] ?? { label: r.state ?? "", tone: "neutral" as const }; return <Badge tone={st.tone}>{st.label}</Badge>; } },
    { key: "o", header: "Connection", cell: (r) => <span className="flex flex-col">
      {r.online ? <Badge tone="success">Online</Badge> : r.lastSeenAt ? <span>Last seen {formatDateTime(r.lastSeenAt)}</span> : "—"}
      {(r.interruptions ?? 0) > 0 && <span className="text-xs text-muted">{r.interruptions} interruption{r.interruptions === 1 ? "" : "s"}, {Math.round((r.interruptedSeconds ?? 0) / 60)} min offline</span>}</span>, hideOnMobile: true },
    { key: "p", header: "Progress", cell: (r) => r.startedAt ? `${r.answered} of ${r.questions} answered` : "—" },
    { key: "t", header: "Time left", cell: (r) => r.state === "IN_PROGRESS" ? <span className="flex flex-col"><span className="tabular-nums">{minutesLeft(r.secondsLeft ?? 0)}</span>
      <span className="text-xs text-muted">Ends {formatDateTime(r.deadlineAt)}</span>
      {(r.extendedMinutes ?? 0) > 0 && <button type="button" className="text-left text-xs text-muted underline" onClick={() => setHistoryFor(r)}>+{r.extendedMinutes} min extra</button>}</span>
      : (r.extendedMinutes ?? 0) > 0 ? <button type="button" className="text-xs text-muted underline" onClick={() => setHistoryFor(r)}>+{r.extendedMinutes} min extra</button> : "—", hideOnMobile: true },
    { key: "i", header: "Incidents", cell: (r) => r.incidents ? <Badge tone="warning">{r.incidents}</Badge> : "—", hideOnMobile: true },
    { key: "a", header: "", cell: (r) => <span className="flex flex-wrap justify-end gap-1">
      <Button variant="ghost" onClick={() => setIncidentFor(r)}>Record incident</Button>
      {r.state === "IN_PROGRESS" && (r.extensionPending ? <Badge tone="warning">Extra time awaiting approval</Badge>
        : x?.canExtend && <Button variant="ghost" onClick={() => setExtendFor(r)}>Extend time</Button>)}
      {r.state === "IN_PROGRESS" && (r.terminationPending ? <Badge tone="warning">Ending requested</Badge>
        : <Button variant="ghost" onClick={() => setEndFor(r)}>End examination</Button>)}</span> },
  ];
  return (
    <Card>
      <CardHeader title="Live monitoring" description={x ? `${c.IN_PROGRESS ?? 0} writing · ${(c.SUBMITTED ?? 0) + (c.AUTO_SUBMITTED ?? 0)} submitted · ${c.CHECKED_IN ?? 0} checked in, not started · ${(c.NOT_CHECKED_IN ?? 0) + (c.NOT_LINKED ?? 0)} not checked in · ${c.ONLINE ?? 0} online · updated ${formatDateTime(x.serverTime)}` : "Loading…"} />
      <DataTable caption="Live monitoring" columns={columns} rows={x?.candidates} rowKey={(r) => r.enrolmentId!} loading={m.isPending}
        empty={{ title: "Nobody enrolled yet", description: "" }} />
      {(inc.data ?? []).length > 0 && (
        <CardBody>
          <h3 className="mb-2 text-sm font-semibold">Incidents</h3>
          <ul className="space-y-1 text-sm" aria-label="Incidents">{inc.data!.map((i: CandidateIncident) => (
            <li key={i.id}>{formatDateTime(i.recordedAt)} · {i.candidateName} · {CATEGORIES.find(([k]) => k === i.category)?.[1] ?? i.category}
              {i.correctsId ? " (correction)" : ""}: {i.note} <span className="text-muted">— {i.recordedByName}</span></li>))}</ul>
        </CardBody>)}
      {requested && <div className="px-5 pt-3"><Alert tone="info">{requested}{" "}<Link to="/approvals" className="underline">Open approvals</Link></Alert></div>}
      {extendFor && <ExtendDialog scheduleId={scheduleId} candidate={extendFor} secondPerson={!!x?.extraTimeSecondPerson} onClose={() => setExtendFor(null)}
        onDone={(pending) => { if (pending) setRequested(`Extra time for ${extendFor.displayName ?? "the candidate"} waits for approval by another person with that right. Their time is unchanged until then.`);
          setExtendFor(null); refreshMonitor(); }} />}
      {historyFor && <HistoryDialog scheduleId={scheduleId} candidate={historyFor} onClose={() => setHistoryFor(null)} />}
      {endFor && <EndDialog scheduleId={scheduleId} candidate={endFor} onClose={() => setEndFor(null)}
        onDone={() => { setRequested(`Ending ${endFor.displayName ?? "the candidate"}'s examination waits for a decision by another person with that right. The candidate keeps writing until then.`); setEndFor(null); refreshMonitor(); }} />}
      {incidentFor && <IncidentDialog scheduleId={scheduleId} candidate={incidentFor} previous={(inc.data ?? []).filter((i) => i.enrolmentId === incidentFor.enrolmentId)}
        onClose={() => setIncidentFor(null)}
        onDone={() => { setIncidentFor(null); void qc.invalidateQueries({ queryKey: ["exam-candidate-incidents", scheduleId] }); void qc.invalidateQueries({ queryKey: ["exam-monitor", scheduleId] }); }} />}
    </Card>
  );
}

function ExtendDialog({ scheduleId, candidate, secondPerson, onClose, onDone }: { scheduleId: string; candidate: InvigilationCandidate; secondPerson: boolean;
  onClose: () => void; onDone: (pending: boolean) => void }) {
  const api = useApi();
  const [minutes, setMinutes] = useState("10");
  const [reason, setReason] = useState("");
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/schedules/{id}/candidates/{enrolmentId}/extensions", { params: { path: { id: scheduleId, enrolmentId: candidate.enrolmentId! } },
      body: { minutes: Number(minutes), reason } })),
    onSuccess: (r) => onDone(r.status === "PENDING_APPROVAL"),
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Extend time for ${candidate.displayName ?? candidate.email}`}
      description={`${secondPerson ? "This examination needs a second person: the time is added when another designated person approves." : "Added to this candidate's running examination now."} Never beyond the end of the examination window. Recorded permanently with its reason; the candidate sees the new end time.`}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!reason.trim()} loading={save.isPending} onClick={() => save.mutate()}>Add time</Button></>}>
      <div className="space-y-4">
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        <Field label="Minutes to add (1–60)">{(p) => <Input {...p} type="number" min={1} max={60} value={minutes} onChange={(ev) => setMinutes(ev.target.value)} />}</Field>
        <Field label="Reason">{(p) => <Input {...p} maxLength={500} value={reason} onChange={(ev) => setReason(ev.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

function EndDialog({ scheduleId, candidate, onClose, onDone }: { scheduleId: string; candidate: InvigilationCandidate; onClose: () => void; onDone: () => void }) {
  const api = useApi();
  const [reason, setReason] = useState("");
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/schedules/{id}/candidates/{enrolmentId}/termination", { params: { path: { id: scheduleId, enrolmentId: candidate.enrolmentId! } },
      body: { reason } })),
    onSuccess: onDone,
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`End the examination of ${candidate.displayName ?? candidate.email}?`}
      description="Another person with the right to end examinations must approve. Until then the candidate keeps writing. Saved answers are always kept."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!reason.trim()} loading={save.isPending} onClick={() => save.mutate()}>Ask for approval</Button></>}>
      <div className="space-y-4">
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        <Field label="Reason">{(p) => <TextArea {...p} rows={3} maxLength={1000} value={reason} onChange={(ev) => setReason(ev.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

function IncidentDialog({ scheduleId, candidate, previous, onClose, onDone }: { scheduleId: string; candidate: InvigilationCandidate; previous: CandidateIncident[]; onClose: () => void; onDone: () => void }) {
  const api = useApi();
  const [category, setCategory] = useState("");
  const [note, setNote] = useState("");
  const [corrects, setCorrects] = useState("");
  const save = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/exam/schedules/{id}/candidate-incidents", { params: { path: { id: scheduleId } },
      body: { enrolmentId: candidate.enrolmentId!, category, note, ...(corrects ? { correctsId: corrects } : {}) } })),
    onSuccess: onDone,
  });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Record an incident for ${candidate.displayName ?? candidate.email}`}
      description="Incidents are permanent and visible to examination staff. To correct one, record a new incident that refers to it."
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!category || !note.trim()} loading={save.isPending} onClick={() => save.mutate()}>Record incident</Button></>}>
      <div className="space-y-4">
        {save.isError && <Alert tone="danger">{errorInfo(save.error).message}</Alert>}
        <Field label="Category">{(p) => <Select {...p} value={category} onChange={(ev) => setCategory(ev.target.value)}>
          <option value="">Choose…</option>{CATEGORIES.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</Select>}</Field>
        <Field label="What happened">{(p) => <TextArea {...p} rows={3} maxLength={1000} value={note} onChange={(ev) => setNote(ev.target.value)} />}</Field>
        {previous.length > 0 && <Field label="Corrects an earlier incident (optional)">{(p) => <Select {...p} value={corrects} onChange={(ev) => setCorrects(ev.target.value)}>
          <option value="">No</option>{previous.map((i) => <option key={i.id} value={i.id}>{formatDateTime(i.recordedAt)}: {i.note?.slice(0, 60)}</option>)}</Select>}</Field>}
      </div>
    </Modal>
  );
}

export default ScheduleDetailPage;

/** Every extra-time grant of one candidate (R-2): permanent records with the previous and new end time. */
function HistoryDialog({ scheduleId, candidate, onClose }: { scheduleId: string; candidate: InvigilationCandidate; onClose: () => void }) {
  const api = useApi();
  const h = useQuery({ queryKey: ["exam-extensions", scheduleId, candidate.enrolmentId], queryFn: async () => unwrap(await api.GET(
    "/api/v1/exam/schedules/{id}/candidates/{enrolmentId}/extensions", { params: { path: { id: scheduleId, enrolmentId: candidate.enrolmentId! } } })) });
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={`Extra time for ${candidate.displayName ?? candidate.email}`}
      footer={<Button variant="secondary" onClick={onClose}>Close</Button>}>
      {h.isError ? <Alert tone="danger">{errorInfo(h.error).message}</Alert> : (
        <ol className="space-y-3 text-sm" aria-label="Extra-time grants">{(h.data ?? []).map((g) => (
          <li key={g.id}>
            <span className="font-medium">+{g.minutes} min</span> · {formatDateTime(g.grantedAt)} · {g.grantedByName}{g.approvedByName ? `, approved by ${g.approvedByName}` : ""}
            <span className="block text-muted">End time {formatDateTime(g.previousExpiry)} → {formatDateTime(g.newExpiry)}</span>
            <span className="block">{g.reason}</span>
          </li>))}</ol>)}
    </Modal>
  );
}
