import { unwrap, type QuestionVersion } from "@ivorovi/api-client";
import { Alert, Badge, Button, ButtonLink, Card, CardBody, CardHeader, ErrorState, Field, Input, Modal, PageHeader, Select, Skeleton, TabPanel, Tabs, TextArea } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Eye, GitBranch, Pencil, Send } from "lucide-react";
import { useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime, humanize } from "../../lib/format";
import { policyText } from "./QuestionBanksPage";
import { asDoc, Content, OptionsView, StateBadge, TYPE_LABEL } from "./QuestionShared";

function VersionView({ v }: { v: QuestionVersion }) {
  const body = v.body as Record<string, unknown> | undefined;
  return (
    <div className="space-y-3">
      <Content doc={asDoc(v.content)} />
      <OptionsView body={body} />
      {v.type === "NUMERIC" && <p className="text-sm text-muted">Numeric answer{body?.unit ? ` in ${String(body.unit)}` : ""}, {String(body?.precision ?? 2)} decimal place(s).</p>}
      <dl className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
        <div><dt className="text-muted">Type</dt><dd>{TYPE_LABEL[v.type ?? ""] ?? v.type}</dd></div>
        <div><dt className="text-muted">Marks</dt><dd>{v.marks}{Number(v.negativeMarks) > 0 ? ` (−${v.negativeMarks})` : ""}</dd></div>
        <div><dt className="text-muted">Author</dt><dd>{v.authorName}</dd></div>
        <div><dt className="text-muted">Approved by</dt><dd>{v.approvedByName ?? "—"}</dd></div>
      </dl>
      {(v.tags ?? []).length > 0 && <p className="flex flex-wrap gap-1">{(v.tags ?? []).map((t) => <Badge key={t}>{t}</Badge>)}</p>}
    </div>
  );
}

function KeyPanel({ questionId, v }: { questionId: string; v: QuestionVersion }) {
  const api = useApi();
  const [shown, setShown] = useState(false);
  const key = useQuery({ queryKey: ["question-key", questionId, v.versionNo], enabled: shown, gcTime: 0, staleTime: 0,
    queryFn: async () => unwrap(await api.GET("/api/v1/questions/{id}/versions/{no}/answer-key", { params: { path: { id: questionId, no: v.versionNo! } } })) });
  const [response, setResponse] = useState("");
  const preview = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/questions/{id}/versions/{no}/marking-preview",
    { params: { path: { id: questionId, no: v.versionNo! } }, body: { responses: [v.type === "MULTIPLE_CHOICE" ? response.split(",").map((s) => s.trim()) : v.type === "TRUE_FALSE" ? response === "true" : response] } })) });
  if (!v.keyVisible) return <p className="text-sm text-muted">You do not have access to this version's answer key.</p>;
  if (!shown) return <Button variant="secondary" onClick={() => setShown(true)}><Eye aria-hidden className="h-4 w-4" /> Show answer key (audited)</Button>;
  if (key.isError) return <Alert tone="danger">{errorInfo(key.error).message}</Alert>;
  if (key.isPending) return <Skeleton className="h-16 w-full" />;
  return (
    <div className="space-y-3 text-sm" data-testid="answer-key">
      <pre className="overflow-x-auto rounded-md bg-bg p-3 text-xs">{JSON.stringify(key.data.key, null, 2)}</pre>
      {key.data.explanation && <p><span className="text-muted">Explanation: </span>{key.data.explanation}</p>}
      <div className="flex items-end gap-2">
        <Field label="Try a response">{(p) => <Input {...p} value={response} onChange={(e) => setResponse(e.target.value)} placeholder={v.type === "MULTIPLE_CHOICE" ? "A, C" : ""} />}</Field>
        <Button variant="secondary" loading={preview.isPending} onClick={() => preview.mutate()}>Mark</Button>
      </div>
      {preview.data?.[0] && <p data-testid="marking-result">{humanize(preview.data[0].outcome)} · {preview.data[0].awarded} marks · {preview.data[0].detail}</p>}
      <Button variant="ghost" size="sm" onClick={() => setShown(false)}>Hide</Button>
    </div>
  );
}

type ActionKind = "submit" | "review" | "changes" | "approve" | "retire" | "version";

export function QuestionDetailPage() {
  const { id = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const api = useApi();
  const qc = useQueryClient();
  const [action, setAction] = useState<ActionKind | null>(null);
  const [text, setText] = useState("");
  const [decision, setDecision] = useState("REVIEWED");
  const q = useQuery({ queryKey: ["question", id], queryFn: async () => unwrap(await api.GET("/api/v1/questions/{id}", { params: { path: { id } } })) });
  const viewNo = Number(params.get("v") ?? 0);
  const other = useQuery({ queryKey: ["question", id, "v", viewNo], enabled: viewNo > 0,
    queryFn: async () => unwrap(await api.GET("/api/v1/questions/{id}/versions/{no}", { params: { path: { id, no: viewNo } } })) });
  const transitions = useQuery({ queryKey: ["question", id, "transitions"], queryFn: async () => unwrap(await api.GET("/api/v1/questions/{id}/transitions", { params: { path: { id } } })) });
  const history = useQuery({ queryKey: ["question", id, "history"], queryFn: async () => unwrap(await api.GET("/api/v1/questions/{id}/history", { params: { path: { id } } })) });
  const done = () => { setAction(null); setText(""); void qc.invalidateQueries({ queryKey: ["question", id] }); void qc.invalidateQueries({ queryKey: ["questions"] }); void qc.invalidateQueries({ queryKey: ["question-queue"] }); };
  const run = useMutation({
    mutationFn: async (kind: ActionKind) => {
      const path = { params: { path: { id } } };
      switch (kind) {
        case "submit": return unwrap(await api.POST("/api/v1/questions/{id}/submit", { ...path, body: { reason: text || undefined } }));
        case "review": return unwrap(await api.POST("/api/v1/questions/{id}/reviews", { ...path, body: { decision: decision as "REVIEWED", comment: text || undefined, checklist: { contentAccurate: true, keyCorrect: true, accessible: true } } }));
        case "changes": return unwrap(await api.POST("/api/v1/questions/{id}/request-changes", { ...path, body: { reason: text } }));
        case "approve": return unwrap(await api.POST("/api/v1/questions/{id}/approve", { ...path, body: { reason: text || undefined } }));
        case "retire": return unwrap(await api.POST("/api/v1/questions/{id}/retire", { ...path, body: { reason: text } }));
        case "version": return unwrap(await api.POST("/api/v1/questions/{id}/versions", path));
      }
    },
    onSuccess: done,
  });
  if (q.isError) return <ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} />;
  if (q.isPending) return <Skeleton className="h-96 w-full" />;
  const d = q.data;
  const cur = d.current!;
  const a = d.actions ?? {};
  const shown = viewNo > 0 && other.data ? other.data : cur;
  const titles: Record<ActionKind, string> = { submit: "Submit for review", review: "Review", changes: "Request changes", approve: "Approve", retire: "Retire question", version: "New version" };
  const needsText = action === "changes" || action === "retire" || (action === "review" && decision === "CHANGES_REQUESTED");
  return (
    <>
      <PageHeader title={`Question · version ${cur.versionNo}`} description={`${d.bankName} · ${policyText(d.effectivePolicy)}`}
        actions={<div className="flex flex-wrap gap-2">
          {a.edit && <ButtonLink variant="secondary" to={`/questions/items/${id}/edit`}><Pencil aria-hidden className="h-4 w-4" /> Edit</ButtonLink>}
          {a.submit && <Button variant="primary" onClick={() => setAction("submit")}><Send aria-hidden className="h-4 w-4" /> Submit for review</Button>}
          {a.review && <Button variant="primary" onClick={() => setAction("review")}>Review</Button>}
          {a.approve && <Button variant="primary" onClick={() => setAction("approve")}>Approve</Button>}
          {a.requestChanges && !a.review && <Button variant="secondary" onClick={() => setAction("changes")}>Request changes</Button>}
          {a.newVersion && <Button variant="secondary" onClick={() => setAction("version")}><GitBranch aria-hidden className="h-4 w-4" /> New version</Button>}
          {a.retire && <Button variant="danger" onClick={() => setAction("retire")}>Retire</Button>}
        </div>} />
      <div className="mb-4 flex flex-wrap items-center gap-2 text-sm">
        <StateBadge state={cur.state} /> {d.status === "RETIRED" && <Badge tone="warning">Retired</Badge>}
        <span className="text-muted-strong">Valid reviews {cur.validReviews} of {d.effectivePolicy?.requiredReviews}</span>
        {a.blockedReason && <span className="text-muted-strong">· {a.blockedReason}</span>}
      </div>
      {run.isError && <Alert tone="danger">{errorInfo(run.error).message}</Alert>}
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card>
            <CardHeader title={viewNo > 0 ? `Version ${viewNo}` : "Current version"} />
            <CardBody>{other.isPending && viewNo > 0 ? <Skeleton className="h-32 w-full" /> : <VersionView v={shown} />}</CardBody>
          </Card>
          {d.approved && viewNo === 0 && (
            <Card>
              <CardHeader title={`Approved version ${d.approved.versionNo} (unchanged)`} description="Assessments use approved versions; the draft above does not affect it." />
              <CardBody><VersionView v={d.approved} /></CardBody>
            </Card>)}
          <Card>
            <CardHeader title="Answer key" />
            <CardBody><KeyPanel questionId={id} v={shown} /></CardBody>
          </Card>
        </div>
        <div className="space-y-6">
          <Card>
            <CardHeader title="Versions" />
            <CardBody>
              <ul className="space-y-2 text-sm">{(d.versions ?? []).map((v) => (
                <li key={v.id} className="flex items-center justify-between gap-2">
                  <button type="button" className="font-medium hover:underline" onClick={() => setParams(v.versionNo === cur.versionNo ? {} : { v: String(v.versionNo) })}>Version {v.versionNo}</button>
                  <StateBadge state={v.state} />
                </li>))}</ul>
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Reviews" />
            <CardBody>
              {(cur.reviews ?? []).length === 0 ? <p className="text-sm text-muted">No reviews yet.</p> : (
                <ul className="space-y-2 text-sm">{(cur.reviews ?? []).map((r) => (
                  <li key={r.id}><span className="font-medium">{r.reviewerName}</span> · {humanize(r.decision)} · {formatDateTime(r.at)}
                    {!r.valid && <Badge tone="warning" className="ml-1">Invalidated</Badge>}{r.comment && <p className="text-muted">“{r.comment}”</p>}</li>))}</ul>)}
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Workflow" />
            <CardBody>
              <Tabs label="Workflow records" value="t" onValueChange={() => undefined} tabs={[{ value: "t", label: "Transitions" }]}>
                <TabPanel value="t">
                  <ol className="space-y-2 text-sm">{(transitions.data ?? []).map((t, i) => (
                    <li key={i}>v{t.versionNo}: {t.fromState ? `${humanize(t.fromState)} → ` : ""}{humanize(t.toState)} · {t.actorName} · {formatDateTime(t.at)}{t.reason && <span className="text-muted"> — {t.reason}</span>}</li>))}</ol>
                </TabPanel>
              </Tabs>
              <details className="mt-3 text-sm"><summary className="cursor-pointer text-muted">Audit history ({history.data?.length ?? 0})</summary>
                <ol className="mt-2 space-y-1">{(history.data ?? []).map((h, i) => <li key={i}>{h.action} · {h.actorName} · {formatDateTime(h.at)}</li>)}</ol></details>
            </CardBody>
          </Card>
        </div>
      </div>
      {action && (
        <Modal open onOpenChange={(o) => !o && setAction(null)} title={titles[action]}
          description={action === "approve" ? "Approval makes this version immutable and the one assessments use." : action === "version" ? "Creates a draft copy; the approved version stays unchanged." : undefined}
          footer={<><Button variant="secondary" onClick={() => setAction(null)}>Cancel</Button>
            <Button variant={action === "retire" ? "danger" : "primary"} loading={run.isPending} disabled={needsText && !text.trim()} onClick={() => run.mutate(action)}>{titles[action]}</Button></>}>
          <div className="space-y-3">
            {run.isError && <Alert tone="danger">{errorInfo(run.error).message}</Alert>}
            {action === "review" && <Field label="Decision">{(p) => <Select {...p} value={decision} onChange={(e) => setDecision(e.target.value)}>
              <option value="REVIEWED">Content, key and accessibility are correct</option><option value="CHANGES_REQUESTED">Changes needed</option></Select>}</Field>}
            {action !== "version" && <Field label={needsText ? "Reason" : "Comment (optional)"}>{(p) => <TextArea {...p} rows={3} maxLength={1000} value={text} onChange={(e) => setText(e.target.value)} />}</Field>}
          </div>
        </Modal>)}
    </>
  );
}

export default QuestionDetailPage;
