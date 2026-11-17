import { unwrap } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, CardHeader, ErrorState, Field, Input, PageHeader, Skeleton, TextArea } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { asDoc, Content } from "../questions/QuestionShared";

/** Mark one anonymous answer against the rubric; a submission is final (contract C-2). */
export function EvaluationTaskPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const qc = useQueryClient();
  const path = { params: { path: { taskId: id } } };
  const t = useQuery({ queryKey: ["evaluation-task", id], queryFn: async () => unwrap(await api.GET("/api/v1/evaluation/tasks/{taskId}", path)) });
  const [marks, setMarks] = useState<Record<string, string>>({});
  const [holistic, setHolistic] = useState("");
  const [comment, setComment] = useState("");
  const submit = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/v1/evaluation/tasks/{taskId}/submission", { ...path, body: rubric.length
      ? { criteria: Object.fromEntries(rubric.map((c) => [c.criterionId!, Number(marks[c.criterionId!] ?? "")])), comment: comment || undefined }
      : { holistic: Number(holistic), comment: comment || undefined } })),
    onSuccess: (d) => { qc.setQueryData(["evaluation-task", id], d); void qc.invalidateQueries({ queryKey: ["evaluation-tasks"] }); },
  });
  if (t.isError) return <ErrorState message={errorInfo(t.error).message} />;
  if (!t.data) return <Skeleton className="h-96 w-full" />;
  const d = t.data;
  const rubric = d.criterionMarks ?? [];
  const submitted = d.status === "SUBMITTED";
  const total = rubric.length ? rubric.reduce((s, c) => s + (Number(marks[c.criterionId!]) || 0), 0) : Number(holistic) || 0;
  const complete = rubric.length ? rubric.every((c) => (marks[c.criterionId!] ?? "").trim() !== "") : holistic.trim() !== "";
  return (
    <>
      <PageHeader title={`Script ${d.scriptCode} · question ${d.itemNo}`} description={`Worth ${d.marks} marks. ${submitted ? "Submitted — final." : "Your submission is final."}`}
        actions={<Link to="/evaluation" className="underline">All my evaluations</Link>} />
      <div className="grid gap-6 xl:grid-cols-2">
        <div className="space-y-6">
          <Card><CardHeader title="Question" /><CardBody><Content doc={asDoc(d.statement)} />
            <p className="mt-2 text-sm text-muted">Word limit: {d.maxWords}</p></CardBody></Card>
          <Card><CardHeader title="Answer" description="As the candidate wrote it." />
            <CardBody><p className="whitespace-pre-wrap" data-testid="evaluation-response">{d.responseText ?? "—"}</p></CardBody></Card>
          {d.modelAnswer && <Card><CardHeader title="Model answer" description="Guidance from the question's author. Your view is recorded." />
            <CardBody><p className="whitespace-pre-wrap text-sm">{d.modelAnswer}</p></CardBody></Card>}
        </div>
        <Card className="xl:sticky xl:top-4 xl:self-start">
          <CardHeader title="Marks" description={rubric.length ? "Mark each rubric criterion." : "No rubric: mark the answer as a whole."} />
          <CardBody className="space-y-3">
            {submit.isError && <Alert tone="danger">{errorInfo(submit.error).message}</Alert>}
            {rubric.map((c) => {
              const criterion = (d.rubric ?? []).find((r) => r.id === c.criterionId);
              return (
                <Field key={c.criterionId} label={`${c.title} (0–${c.max})`} hint={criterion?.description ?? undefined}>{(f) =>
                  <Input {...f} inputMode="decimal" disabled={submitted} value={submitted ? String(c.awarded ?? "") : (marks[c.criterionId!] ?? "")}
                    onChange={(e) => setMarks({ ...marks, [c.criterionId!]: e.target.value })} className="w-32" />}</Field>);
            })}
            {rubric.length === 0 && <Field label={`Mark (0–${d.marks})`}>{(f) => <Input {...f} inputMode="decimal" disabled={submitted}
              value={submitted ? String(d.total ?? "") : holistic} onChange={(e) => setHolistic(e.target.value)} className="w-32" />}</Field>}
            <Field label="Comment (optional)">{(f) => <TextArea {...f} rows={3} maxLength={2000} disabled={submitted} value={submitted ? (d.comment ?? "") : comment}
              onChange={(e) => setComment(e.target.value)} />}</Field>
            <p className="text-sm font-medium" data-testid="evaluation-total">Total: {submitted ? d.total : Math.round(total * 100) / 100} / {d.marks}</p>
            {!submitted && <Button variant="primary" loading={submit.isPending} disabled={!complete} onClick={() => submit.mutate()}>Submit marks</Button>}
          </CardBody>
        </Card>
      </div>
    </>
  );
}

export default EvaluationTaskPage;
