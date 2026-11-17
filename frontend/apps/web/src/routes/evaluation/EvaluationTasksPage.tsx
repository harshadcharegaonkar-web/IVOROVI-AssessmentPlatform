import { unwrap, type EvaluationTaskSummary } from "@ivorovi/api-client";
import { Badge, Card, DataTable, ErrorState, PageHeader, type Column } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";

/** The evaluator's own tasks: anonymous scripts only (contract C-2). */
export function EvaluationTasksPage() {
  const api = useApi();
  const q = useQuery({ queryKey: ["evaluation-tasks"], queryFn: async () => unwrap(await api.GET("/api/v1/evaluation/tasks")) });
  if (q.isError) return <ErrorState message={errorInfo(q.error).message} />;
  const columns: Column<EvaluationTaskSummary>[] = [
    { key: "s", header: "Script", cell: (t) => <Link className="font-medium underline" to={`/evaluation/tasks/${t.taskId}`}>{t.scriptCode} · question {t.itemNo}</Link> },
    { key: "m", header: "Marks", cell: (t) => t.marks },
    { key: "st", header: "Status", cell: (t) => t.status === "SUBMITTED" ? <Badge tone="success">Submitted</Badge> : <Badge tone="warning">To mark</Badge> },
    { key: "a", header: "Allocated", cell: (t) => formatDateTime(t.assignedAt), hideOnMobile: true },
  ];
  const open = (q.data ?? []).filter((t) => t.status !== "SUBMITTED").length;
  return (
    <>
      <PageHeader title="My evaluations" description={`${open} answer(s) to mark. Scripts are anonymous: you never see who wrote an answer.`} />
      <Card><DataTable caption="My evaluations" columns={columns} rows={q.data} rowKey={(t) => t.taskId!} loading={q.isPending}
        empty={{ title: "Nothing to mark", description: "Answers appear here when an examination controller allocates scripts to you." }} /></Card>
    </>
  );
}

export default EvaluationTasksPage;
