import { unwrap, type EnrolmentUploadResult, type ExamEnrolment } from "@ivorovi/api-client";
import { Alert, Badge, Button, Card, CardBody, CardHeader, DataTable, Field, Input, Modal, PageHeader, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
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
  const path = { params: { path: { id } } };
  const s = useQuery({ queryKey: ["exam-schedule", id], queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules/{id}", path)) });
  const e = useQuery({ queryKey: ["exam-enrolments", id], queryFn: async () => unwrap(await api.GET("/api/v1/exam/schedules/{id}/enrolments", path)) });
  const refresh = () => { void qc.invalidateQueries({ queryKey: ["exam-schedule", id] }); void qc.invalidateQueries({ queryKey: ["exam-enrolments", id] }); };
  const [csv, setCsv] = useState("");
  const [preview, setPreview] = useState<EnrolmentUploadResult | null>(null);
  const [extraFor, setExtraFor] = useState<ExamEnrolment | null>(null);
  const [codes, setCodes] = useState<{ code?: string; hint?: string }[] | null>(null);
  const [cancelling, setCancelling] = useState(false);
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
            </dl>
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

        <Card>
          <CardHeader title={`Enrolled candidates (${e.data?.filter((r) => r.status === "ACTIVE").length ?? 0})`}
            actions={manage && open && <div className="flex flex-wrap gap-2">
              <Button variant="secondary" loading={invite.isPending} onClick={() => invite.mutate()}>Send invitations</Button>
              {access.hasAnywhere("exam.session.manage") && <Button variant="secondary" loading={issue.isPending} onClick={() => issue.mutate()}>Issue session codes</Button>}
            </div>} />
          {invite.data && <div className="px-5 pt-3"><Alert tone={invite.data.failed ? "warning" : "success"}>Invitations sent: {invite.data.sent}{invite.data.failed ? `, failed: ${invite.data.failed}` : ""}.</Alert></div>}
          {invite.isError && <div className="px-5 pt-3"><Alert tone="danger">{errorInfo(invite.error).message}</Alert></div>}
          <DataTable caption="Enrolled candidates" columns={columns} rows={e.data} rowKey={(r) => r.id!} loading={e.isPending}
            error={e.isError ? errorInfo(e.error) : null} empty={{ title: "Nobody enrolled yet", description: "Upload a list of candidates above." }} />
        </Card>
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

export default ScheduleDetailPage;
