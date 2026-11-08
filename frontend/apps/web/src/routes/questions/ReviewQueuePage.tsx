import { unwrap, type QueueItem } from "@ivorovi/api-client";
import { ButtonLink, Card, DataTable, PageHeader, TabPanel, Tabs, type Column } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatRelative } from "../../lib/format";
import { useSession } from "../../session/SessionProvider";
import { QuestionTabs, StateBadge, TYPE_LABEL } from "./QuestionShared";

/** Versions waiting for the caller's review or approval; never the caller's own questions. */
export function ReviewQueuePage() {
  const api = useApi();
  const { access } = useSession();
  const [stage, setStage] = useState(access.hasAnywhere("question.review") ? "review" : "approval");
  const q = useQuery({ queryKey: ["question-queue", stage], queryFn: async () => unwrap(await api.GET("/api/v1/question-review-queue", { params: { query: { stage } } })) });
  const columns: Column<QueueItem>[] = [
    { key: "q", header: "Question", cell: (i) => <ButtonLink variant="ghost" className="h-auto whitespace-normal px-0 py-1 text-left font-medium" to={`/questions/items/${i.questionId}`}>{i.excerpt || "(empty)"}</ButtonLink> },
    { key: "bank", header: "Bank", cell: (i) => i.bankName, hideOnMobile: true },
    { key: "type", header: "Type", cell: (i) => TYPE_LABEL[i.type ?? ""] ?? i.type, hideOnMobile: true },
    { key: "state", header: "State", cell: (i) => <span className="flex items-center gap-2"><StateBadge state={i.state} /><span className="text-xs text-muted">{i.validReviews}/{i.requiredReviews} reviews</span></span> },
    { key: "author", header: "Author", cell: (i) => `${i.authorName ?? ""} · ${formatRelative(i.submittedAt)}`, hideOnMobile: true },
  ];
  const tabs = [
    ...(access.hasAnywhere("question.review") ? [{ value: "review", label: "To review" }] : []),
    ...(access.hasAnywhere("question.approve") ? [{ value: "approval", label: "To approve" }] : []),
  ];
  return (
    <>
      <PageHeader title="Question bank" description="Questions written by others that are waiting for you." />
      <QuestionTabs>
        <Tabs label="Queue" value={stage} onValueChange={setStage} tabs={tabs}>
          <TabPanel value={stage}>
            <Card>
              <DataTable caption={stage === "review" ? "Waiting for review" : "Waiting for approval"} columns={columns} rows={q.data} rowKey={(i) => i.versionId!}
                loading={q.isPending} error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
                empty={{ title: "Nothing waiting", description: "Questions you wrote never appear here: someone else reviews and approves them." }} />
            </Card>
          </TabPanel>
        </Tabs>
      </QuestionTabs>
    </>
  );
}

export default ReviewQueuePage;
