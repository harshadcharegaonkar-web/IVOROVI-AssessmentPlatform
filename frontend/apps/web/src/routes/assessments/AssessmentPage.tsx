import { unwrap, type AssessmentDetail, type AssessmentItem, type AssessmentPaperSummary, type AssessmentRule, type AssessmentTransition, type AssessmentVersion } from "@ivorovi/api-client";
import { Alert, Badge, Button, ButtonLink, Card, CardBody, CardHeader, DataTable, Field, Modal, PageHeader, TabPanel, Tabs, TextArea, type Column } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate, useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { TYPE_LABEL } from "../questions/QuestionShared";
import { AssessmentStateBadge, GovernanceBadge, PURPOSE_LABEL, STATE_LABEL, marks } from "./AssessmentShared";

type Action = "submit" | "approve" | "changes" | "publish";

/** One assessment: current version, workflow actions for the caller, validation, definition, reviews, versions and history. */
export function AssessmentPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [tab, setTab] = useState("definition");
  const [action, setAction] = useState<Action | null>(null);
  const q = useQuery({ queryKey: ["assessment", id], queryFn: async () => unwrap(await api.GET("/api/v1/assessments/{id}", { params: { path: { id } } })) });
  const d = q.data;
  const open = d && ["DRAFT", "IN_REVIEW"].includes(d.current?.state ?? "");
  const validation = useQuery({
    queryKey: ["assessment-validation", id, d?.current?.version], enabled: !!open,
    queryFn: async () => unwrap(await api.GET("/api/v1/assessments/{id}/validation", { params: { path: { id } } })),
  });
  const refresh = () => { void qc.invalidateQueries({ queryKey: ["assessment", id] }); void qc.invalidateQueries({ queryKey: ["assessments"] }); void qc.invalidateQueries({ queryKey: ["assessment-queue"] }); };
  const newVersion = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/assessments/{id}/versions", { params: { path: { id } } })),
    onSuccess: () => { refresh(); void navigate(`/assessments/${id}/edit`); },
  });
  const preview = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/assessments/{id}/preview-papers", { params: { path: { id } } })),
    onSuccess: (p) => void navigate(`/assessments/papers/${p.id}`),
  });

  if (q.isError) return <><PageHeader title="Assessment" /><Alert tone="danger">{errorInfo(q.error).message}</Alert></>;
  if (!d) return <PageHeader title="Assessment" description="Loading…" />;
  const v = d.current!;
  const a = d.actions!;
  return (
    <>
      <PageHeader title={v.title ?? d.code ?? "Assessment"}
        description={<span className="flex flex-wrap items-center gap-2">{d.code} · {PURPOSE_LABEL[d.purpose ?? ""] ?? d.purpose} · {d.orgUnitName}
          <span>· version {v.versionNo}</span><AssessmentStateBadge state={v.state} /><GovernanceBadge governance={v.governance} /></span>}
        actions={<div className="flex flex-wrap gap-2">
          {a.edit && <ButtonLink variant="secondary" to={`/assessments/${id}/edit`}>Edit draft</ButtonLink>}
          {a.submit && <Button variant="primary" onClick={() => setAction("submit")}>Submit for review</Button>}
          {a.review && <><Button variant="primary" onClick={() => setAction("approve")}>Mark as reviewed</Button>
            <Button variant="secondary" onClick={() => setAction("changes")}>Request changes</Button></>}
          {a.publish && <Button variant="primary" onClick={() => setAction("publish")}>Approve and publish</Button>}
          {a.newVersion && <Button variant="secondary" loading={newVersion.isPending} onClick={() => newVersion.mutate()}>New version</Button>}
          <Button variant="secondary" loading={preview.isPending} onClick={() => preview.mutate()}>Preview a paper</Button>
        </div>} />
      <div className="space-y-4">
        {a.blockedReason && <Alert tone="info">{a.blockedReason}</Alert>}
        {(d.warnings ?? []).map((w) => <Alert key={w} tone="warning">{w}</Alert>)}
        {newVersion.isError && <Alert tone="danger">{errorInfo(newVersion.error).message}</Alert>}
        {preview.isError && <Alert tone="danger">{errorInfo(preview.error).message}</Alert>}
        {open && validation.data && <ValidationCard v={validation.data} />}
        <Tabs label="Assessment sections" value={tab} onValueChange={setTab} tabs={[
          { value: "definition", label: "Definition" }, { value: "reviews", label: `Reviews (${v.reviews?.length ?? 0})` },
          { value: "versions", label: "Versions" }, { value: "history", label: "History" }, { value: "papers", label: "Preview papers" }]}>
          <TabPanel value="definition"><Definition v={v} />{d.published && <PublishedNote d={d} />}</TabPanel>
          <TabPanel value="reviews"><Reviews v={v} /></TabPanel>
          <TabPanel value="versions"><Versions d={d} /></TabPanel>
          <TabPanel value="history"><History id={id} /></TabPanel>
          <TabPanel value="papers"><Papers id={id} /></TabPanel>
        </Tabs>
      </div>
      {action && <ActionDialog id={id} action={action} governance={v.governance ?? ""} onClose={() => setAction(null)} onDone={() => { setAction(null); refresh(); }} />}
    </>
  );
}

function ValidationCard({ v }: { v: { errors?: { code?: string; message?: string }[]; warnings?: { code?: string; message?: string }[]; questionCount?: number; totalMarks?: number; byDifficulty?: Record<string, number> } }) {
  const ok = (v.errors ?? []).length === 0;
  return (
    <Card>
      <CardHeader title="Validation" description={`${v.questionCount ?? 0} questions · ${marks(v.totalMarks)} marks · ${Object.entries(v.byDifficulty ?? {}).map(([k, n]) => `${k}: ${n}`).join(" · ") || "no difficulty data"}`}
        actions={ok ? <Badge tone="success">Ready to submit</Badge> : <Badge tone="danger">{v.errors!.length} to fix</Badge>} />
      {((v.errors ?? []).length > 0 || (v.warnings ?? []).length > 0) && (
        <CardBody>
          <ul className="space-y-2 text-sm">
            {(v.errors ?? []).map((e, i) => <li key={`e${i}`}><Alert tone="danger">{e.message}</Alert></li>)}
            {(v.warnings ?? []).map((e, i) => <li key={`w${i}`}><Alert tone="warning">{e.message}</Alert></li>)}
          </ul>
        </CardBody>)}
    </Card>
  );
}

export function Definition({ v }: { v: AssessmentVersion }) {
  const items: Column<AssessmentItem>[] = [
    { key: "n", header: "#", cell: (i) => i.ordinal },
    { key: "q", header: "Question", cell: (i) => <span className="flex flex-col"><span>{i.excerpt || "(question)"}</span>
      {!i.usable && <span className="text-xs text-warning-strong">{STATE_LABEL[i.questionState ?? ""] ?? i.questionState?.toLowerCase()} — no longer the approved version</span>}</span> },
    { key: "t", header: "Type", cell: (i) => TYPE_LABEL[i.type ?? ""] ?? i.type, hideOnMobile: true },
    { key: "c", header: "Subject · difficulty", cell: (i) => [i.subjectName, i.difficultyLabel].filter(Boolean).join(" · ") || "—", hideOnMobile: true },
    { key: "m", header: "Marks", cell: (i) => `${marks(i.marks)}${i.negativeMarks ? ` (−${marks(i.negativeMarks)})` : ""}` },
  ];
  const rules: Column<AssessmentRule>[] = [
    { key: "l", header: "Rule", cell: (r) => r.label || `Random rule ${r.ordinal}` },
    { key: "b", header: "Bank", cell: (r) => r.bankName, hideOnMobile: true },
    { key: "f", header: "Filters", cell: (r) => [r.questionType && (TYPE_LABEL[r.questionType] ?? r.questionType), r.difficultyId && "difficulty", r.subjectId && "subject", r.topicId && "topic"].filter(Boolean).join(", ") || "Any approved question", hideOnMobile: true },
    { key: "d", header: "Draw", cell: (r) => { const size = r.poolSize ?? -1; const n = r.count ?? 0; return <span>{n} of {size < 0 ? "?" : size}{r.poolFrozen && <Badge tone="neutral" className="ml-2">Frozen</Badge>}
      {size >= 0 && size < 3 * n && <Badge tone="warning" className="ml-2">Small pool</Badge>}</span>; } },
    { key: "m", header: "Marks each", cell: (r) => `${marks(r.marks)}${r.negativeMarks ? ` (−${marks(r.negativeMarks)})` : ""}` },
  ];
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="Settings" />
        <CardBody>
          <dl className="grid gap-3 text-sm sm:grid-cols-3">
            <div><dt className="text-muted">Duration</dt><dd>{v.durationMinutes} minutes</dd></div>
            <div><dt className="text-muted">Total marks</dt><dd>{marks(v.totalMarks ?? v.declaredTotalMarks)}{v.declaredTotalMarks ? ` (declared ${marks(v.declaredTotalMarks)})` : ""}</dd></div>
            <div><dt className="text-muted">Negative marking</dt><dd>{{ OFF: "Off", QUESTION: "As set on each question", CUSTOM: "Set in this assessment" }[v.negativeMarking ?? ""] ?? v.negativeMarking}</dd></div>
            <div><dt className="text-muted">Question order</dt><dd>{v.shuffleQuestions ? "Shuffled per candidate" : "As listed"}</dd></div>
            <div><dt className="text-muted">Option order</dt><dd>{v.shuffleOptions ? "Shuffled (unless a question forbids it)" : "As authored"}</dd></div>
            <div><dt className="text-muted">Author</dt><dd>{v.authorName ?? "—"}</dd></div>
          </dl>
          {v.instructions && <p className="mt-4 whitespace-pre-line text-sm"><span className="font-medium">Instructions: </span>{v.instructions}</p>}
        </CardBody>
      </Card>
      <Card><DataTable caption="Fixed questions" columns={items} rows={v.items} rowKey={(i) => i.id!} empty={{ title: "No fixed questions", description: "Every candidate gets the fixed questions." }} /></Card>
      <Card><DataTable caption="Random rules" columns={rules} rows={v.rules} rowKey={(r) => r.id!} empty={{ title: "No random rules", description: "A rule draws questions from a pool, differently for each candidate." }} /></Card>
    </div>
  );
}

function PublishedNote({ d }: { d: AssessmentDetail }) {
  const p = d.published!;
  return <Alert tone="info" className="mt-4">Version {p.versionNo} is published (by {p.publishedByName ?? "—"}, {formatRelative(p.publishedAt)}) and stays in use until this version is published.</Alert>;
}

function Reviews({ v }: { v: AssessmentVersion }) {
  if (!(v.reviews ?? []).length) return <Card><CardBody><p className="text-sm text-muted">No reviews yet.</p></CardBody></Card>;
  return (
    <Card><CardBody>
      <ul className="space-y-3">{v.reviews!.map((r) => (
        <li key={r.id} className="rounded-lg border border-border p-3 text-sm">
          <div className="flex flex-wrap items-center gap-2"><span className="font-medium">{r.reviewerName}</span>
            <Badge tone={r.decision === "REVIEWED" ? "success" : "warning"}>{r.decision === "REVIEWED" ? "Reviewed" : "Changes requested"}</Badge>
            {!r.valid && <Badge tone="neutral">No longer valid{r.invalidatedReason ? `: ${r.invalidatedReason}` : " (content changed)"}</Badge>}
            <span className="text-muted">{formatRelative(r.at)}</span></div>
          {r.comment && <p className="mt-1 whitespace-pre-line">{r.comment}</p>}
        </li>))}</ul>
    </CardBody></Card>
  );
}

function Versions({ d }: { d: AssessmentDetail }) {
  return (
    <Card><CardBody>
      <ul className="divide-y divide-border text-sm">{(d.versions ?? []).map((x) => (
        <li key={x.id} className="flex flex-wrap items-center gap-2 py-2"><span className="font-medium">Version {x.versionNo}</span><AssessmentStateBadge state={x.state} />
          <span className="text-muted">by {x.authorName ?? "—"} · created {formatRelative(x.createdAt)}{x.publishedAt ? ` · published ${formatRelative(x.publishedAt)}` : ""}</span></li>))}</ul>
    </CardBody></Card>
  );
}

function History({ id }: { id: string }) {
  const api = useApi();
  const t = useQuery({ queryKey: ["assessment-transitions", id], queryFn: async () => unwrap(await api.GET("/api/v1/assessments/{id}/transitions", { params: { path: { id } } })) });
  const columns: Column<AssessmentTransition>[] = [
    { key: "v", header: "Version", cell: (x) => x.versionNo },
    { key: "s", header: "Change", cell: (x) => `${x.fromState ? (STATE_LABEL[x.fromState] ?? x.fromState) + " → " : ""}${STATE_LABEL[x.toState ?? ""] ?? x.toState}` },
    { key: "a", header: "By", cell: (x) => x.actorName ?? "—" },
    { key: "r", header: "Reason", cell: (x) => x.reason ?? "", hideOnMobile: true },
    { key: "t", header: "When", cell: (x) => formatRelative(x.at) },
  ];
  return <Card><DataTable caption="State changes" columns={columns} rows={t.data} rowKey={(x) => `${x.versionNo}-${x.at}-${x.toState}`} loading={t.isPending}
    error={t.isError ? errorInfo(t.error) : null} empty={{ title: "No changes yet", description: "" }} /></Card>;
}

function Papers({ id }: { id: string }) {
  const api = useApi();
  const p = useQuery({ queryKey: ["assessment-papers", id], queryFn: async () => unwrap(await api.GET("/api/v1/assessments/{id}/preview-papers", { params: { path: { id } } })), retry: false });
  const columns: Column<AssessmentPaperSummary>[] = [
    { key: "p", header: "Paper", cell: (x) => <ButtonLink variant="ghost" className="px-0 font-mono text-xs" to={`/assessments/papers/${x.id}`}>{x.hash?.slice(0, 16)}…</ButtonLink> },
    { key: "v", header: "Version", cell: (x) => x.versionNo },
    { key: "q", header: "Questions · marks", cell: (x) => `${x.questions} · ${marks(x.totalMarks)}` },
    { key: "b", header: "Generated", cell: (x) => `${x.createdBy ?? ""} · ${formatRelative(x.createdAt)}`, hideOnMobile: true },
  ];
  if (p.isError) return <Alert tone="info">Paper previews are available to authors, reviewers and publishers of this assessment.</Alert>;
  return <Card><DataTable caption="Preview papers" columns={columns} rows={p.data} rowKey={(x) => x.id!} loading={p.isPending}
    empty={{ title: "No preview yet", description: "Preview a paper to see what one candidate would get. Previews never show answers." }} /></Card>;
}

function ActionDialog({ id, action, governance, onClose, onDone }: { id: string; action: Action; governance: string; onClose: () => void; onDone: () => void }) {
  const api = useApi();
  const [text, setText] = useState("");
  const run = useMutation({
    mutationFn: async () => {
      const path = { params: { path: { id } } };
      if (action === "submit") return unwrap(await api.POST("/api/v1/assessments/{id}/submit", { ...path, body: { reason: text || undefined } }));
      if (action === "publish") return unwrap(await api.POST("/api/v1/assessments/{id}/publish", { ...path, body: { reason: text || undefined } }));
      return unwrap(await api.POST("/api/v1/assessments/{id}/reviews", { ...path, body: { decision: action === "approve" ? "REVIEWED" : "CHANGES_REQUESTED", comment: text || undefined } }));
    },
    onSuccess: onDone,
  });
  const copy: Record<Action, [string, string, string, string]> = {
    submit: ["Submit for review", "The version is validated again. Someone else reviews it; you can no longer review or publish it yourself.", "Note for reviewers (optional)", "Submit"],
    approve: ["Mark as reviewed", "Confirms that the questions, rules, marks and settings are right. Any later edit makes this review invalid.", "Comment (optional)", "Mark as reviewed"],
    changes: ["Request changes", "The version returns to draft for its author.", "What needs to change", "Request changes"],
    publish: ["Approve and publish", governance === "HIGH_STAKES"
      ? "High-stakes: you must be neither the author nor a reviewer. Validation runs again, random pools are frozen and the version becomes immutable."
      : "Validation runs again, random pools are frozen and the version becomes immutable. You cannot publish a version you wrote.", "Reason (recorded)", "Publish"],
  };
  const [title, description, label, button] = copy[action];
  return (
    <Modal open onOpenChange={(o) => !o && onClose()} title={title} description={description}
      footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button>
        <Button variant={action === "changes" ? "secondary" : "primary"} loading={run.isPending} disabled={action === "changes" && !text.trim()} onClick={() => run.mutate()}>{button}</Button></>}>
      <div className="space-y-4">
        {run.isError && <Alert tone="danger">{errorInfo(run.error).message}</Alert>}
        <Field label={label}>{(p) => <TextArea {...p} rows={3} maxLength={action === "submit" || action === "publish" ? 500 : 2000} value={text} onChange={(e) => setText(e.target.value)} />}</Field>
      </div>
    </Modal>
  );
}

export default AssessmentPage;
