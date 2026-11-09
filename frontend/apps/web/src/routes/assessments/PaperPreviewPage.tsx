import { unwrap } from "@ivorovi/api-client";
import { Alert, Badge, Button, ButtonLink, Card, CardBody, CardHeader, PageHeader } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { Content, OptionsView, TYPE_LABEL, asDoc } from "../questions/QuestionShared";
import { AssessmentStateBadge, marks } from "./AssessmentShared";

/** One generated paper as a candidate would get it (question order and option order as drawn). Never shows answers. */
export function PaperPreviewPage() {
  const { paperId = "" } = useParams();
  const api = useApi();
  const { access } = useSession();
  const q = useQuery({ queryKey: ["assessment-paper", paperId], queryFn: async () => unwrap(await api.GET("/api/v1/assessment-papers/{paperId}", { params: { path: { paperId } } })) });
  const reproduce = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/assessment-papers/{paperId}/reproduce", { params: { path: { paperId } } })) });
  const seed = useMutation({ mutationFn: async () => unwrap(await api.POST("/api/v1/assessment-papers/{paperId}/seed-disclosures", { params: { path: { paperId } } })) });
  if (q.isError) return <><PageHeader title="Paper preview" /><Alert tone="danger">{errorInfo(q.error).message}</Alert></>;
  const p = q.data;
  if (!p) return <PageHeader title="Paper preview" description="Loading…" />;
  return (
    <>
      <PageHeader title={`Paper preview — ${p.title}`}
        description={<span className="flex flex-wrap items-center gap-2">Version {p.versionNo} <AssessmentStateBadge state={p.versionState} /> · {p.items?.length} questions · {marks(p.totalMarks)} marks · {p.durationMinutes} minutes · generated {formatRelative(p.createdAt)}</span>}
        actions={<div className="flex flex-wrap gap-2">
          <ButtonLink variant="secondary" to={`/assessments/${p.assessmentId}`}>Back to assessment</ButtonLink>
          <Button variant="secondary" loading={reproduce.isPending} onClick={() => reproduce.mutate()}>Reproduce from seed</Button>
          {access.hasAnywhere("assessment.publish") && <Button variant="secondary" loading={seed.isPending} onClick={() => seed.mutate()}>Disclose seed (audited)</Button>}
        </div>} />
      <div className="space-y-4">
        <Alert tone="info">Preview only: answers, explanations and marking rules are never part of a paper. Paper fingerprint <span className="font-mono text-xs">{p.hash}</span></Alert>
        {reproduce.data && <Alert tone={reproduce.data.identical ? "success" : "warning"}>{reproduce.data.note}</Alert>}
        {reproduce.isError && <Alert tone="danger">{errorInfo(reproduce.error).message}</Alert>}
        {seed.data && <Alert tone="warning">Seed (recorded in the audit trail): <span className="break-all font-mono text-xs">{seed.data.seedBase64}</span> · generator v{seed.data.generatorVersion}</Alert>}
        {seed.isError && <Alert tone="danger">{errorInfo(seed.error).message}</Alert>}
        {p.instructions && <Card><CardBody><p className="whitespace-pre-line text-sm">{p.instructions}</p></CardBody></Card>}
        <ol className="space-y-4">
          {(p.items ?? []).map((i) => (
            <li key={`${i.position}-${i.questionVersionId}`}>
              <Card>
                <CardHeader title={`Question ${i.position}`} description={TYPE_LABEL[i.type ?? ""] ?? i.type}
                  actions={<span className="flex items-center gap-2"><Badge tone="neutral">{marks(i.marks)} marks</Badge>{(i.negativeMarks ?? 0) > 0 && <Badge tone="warning">−{marks(i.negativeMarks)}</Badge>}
                    {i.source?.startsWith("RULE") && <Badge tone="info">Random</Badge>}</span>} />
                <CardBody>
                  <Content doc={asDoc(i.content)} className="space-y-2" />
                  <OptionsView body={i.projection as Record<string, unknown> | undefined} />
                </CardBody>
              </Card>
            </li>))}
        </ol>
      </div>
    </>
  );
}

export default PaperPreviewPage;
