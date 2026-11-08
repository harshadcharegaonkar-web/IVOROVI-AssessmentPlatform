import { unwrap, type ApprovalRequest } from "@ivorovi/api-client";
import { Card, DataTable, Link, PageHeader, TabPanel, Tabs, type Column } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { formatDateTime } from "../../lib/format";
import { StatusBadge } from "../integrations/IntegrationShared";

/** Four-eyes inbox (C-2): requests I may decide (never my own) and requests I made. */
export function ApprovalsPage() {
  const api = useApi();
  const [params, setParams] = useSearchParams();
  const view = params.get("view") === "requests" ? "requests" : "decide";
  const q = useQuery({ queryKey: ["approvals", view], queryFn: async () => unwrap(await api.GET("/api/v1/approvals", { params: { query: { view } } })) });
  const columns: Column<ApprovalRequest>[] = [
    { key: "summary", header: "Request", cell: (r) => <Link to={`/approvals/${r.id}`} className="font-medium text-fg hover:underline">{r.summary}</Link> },
    { key: "type", header: "Type", cell: (r) => r.typeLabel },
    { key: "status", header: "Status", cell: (r) => <StatusBadge status={r.status} /> },
    { key: "by", header: view === "decide" ? "Requested by" : "Decided by", cell: (r) => (view === "decide" ? r.requestedByName : r.decidedByName ?? "—"), hideOnMobile: true },
    { key: "at", header: "Requested", cell: (r) => formatDateTime(r.requestedAt), hideOnMobile: true },
    { key: "expires", header: "Expires", cell: (r) => formatDateTime(r.expiresAt), hideOnMobile: true },
  ];
  return (
    <>
      <PageHeader title="Approvals" description="Changes that need a second person. Nobody can approve their own request; the approver must hold the required permission when deciding." />
      <Tabs label="Approval view" value={view} onValueChange={(v) => setParams(v === "decide" ? {} : { view: v }, { replace: true })}
        tabs={[{ value: "decide", label: "To decide", count: view === "decide" ? q.data?.length : undefined }, { value: "requests", label: "My requests" }]}>
        <TabPanel value={view}>
          <Card>
            <DataTable caption={view === "decide" ? "Requests waiting for my decision" : "My requests"} columns={columns} rows={q.data} rowKey={(r) => r.id!}
              loading={q.isPending} error={q.isError ? errorInfo(q.error) : null} onRetry={() => void q.refetch()}
              empty={{ title: view === "decide" ? "Nothing to decide" : "No requests", description: view === "decide" ? "Requests from other people that you may approve appear here." : "Requests you make appear here." }} />
          </Card>
        </TabPanel>
      </Tabs>
    </>
  );
}

export default ApprovalsPage;
