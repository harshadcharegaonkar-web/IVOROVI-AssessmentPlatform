import { unwrap, type AssessmentQueueItem } from "@ivorovi/api-client";
import { ButtonLink, Card, DataTable, PageHeader, TabPanel, Tabs, type Column } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { AssessmentStateBadge, AssessmentTabs, HIGH_STAKES, PURPOSE_LABEL } from "./AssessmentShared";

/** Versions waiting for the caller's review or publication; never the caller's own, never one the caller must not publish. */
export function AssessmentQueuePage() {
  const api = useApi();
  const { access } = useSession();
  const [stage, setStage] = useState(access.hasAnywhere("assessment.review") ? "review" : "publication");
  const q = useQuery({ queryKey: ["assessment-queue", stage], queryFn: async () => unwrap(await api.GET("/api/v1/assessment-queue", { params: { query: { stage } } })) });
  const columns: Column<AssessmentQueueItem>[] = [
    { key: "t", header: "Assessment", cell: (i) => (
      <span className="flex flex-col">
        <ButtonLink variant="ghost" className="h-auto px-0 py-0 text-left font-medium" to={`/assessments/${i.assessmentId}`}>{i.title}</ButtonLink>
        <span className="text-xs text-muted">{i.code} · version {i.versionNo}</span>
      </span>) },
    { key: "p", header: "Purpose", cell: (i) => `${PURPOSE_LABEL[i.purpose ?? ""] ?? i.purpose}${HIGH_STAKES.includes(i.purpose ?? "") ? " (high-stakes)" : ""}`, hideOnMobile: true },
    { key: "s", header: "State", cell: (i) => <span className="flex items-center gap-2"><AssessmentStateBadge state={i.state} /><span className="text-xs text-muted">{i.validReviews} valid review{i.validReviews === 1 ? "" : "s"}</span></span> },
    { key: "a", header: "Author", cell: (i) => `${i.authorName ?? ""} · ${formatRelative(i.submittedAt)}`, hideOnMobile: true },
  ];
  const tabs = [
    ...(access.hasAnywhere("assessment.review") ? [{ value: "review", label: "To review" }] : []),
    ...(access.hasAnywhere("assessment.publish") ? [{ value: "publication", label: "To publish" }] : []),
  ];
  return (
    <>
      <PageHeader title="Assessments" description="Assessments written by others that are waiting for you." />
      <AssessmentTabs>
        <Tabs label="Queue" value={stage} onValueChange={setStage} tabs={tabs}>
          <TabPanel value={stage}>
            <Card>
              <DataTable caption={stage === "review" ? "Waiting for review" : "Waiting for publication"} columns={columns} rows={q.data} rowKey={(i) => i.versionId!}
                loading={q.isPending} error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
                empty={{ title: "Nothing waiting", description: stage === "review"
                  ? "Assessments you wrote never appear here: someone else reviews them."
                  : "High-stakes assessments you reviewed are published by someone else, so they do not appear here." }} />
            </Card>
          </TabPanel>
        </Tabs>
      </AssessmentTabs>
    </>
  );
}

export default AssessmentQueuePage;
