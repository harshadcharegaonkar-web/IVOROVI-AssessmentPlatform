import { unwrap } from "@ivorovi/api-client";
import { Card, CardBody, CardHeader, EmptyState, ErrorState, Link, PageHeader, Skeleton } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { FileText, ShieldCheck } from "lucide-react";
import { errorInfo, useApi } from "../../api/ApiProvider";

/** Report catalogue: only reports whose underlying records the viewer may read. */
export function ReportsPage() {
  const api = useApi();
  const q = useQuery({ queryKey: ["reports", "catalogue"], queryFn: async () => unwrap(await api.GET("/api/v1/reports")) });
  return (
    <>
      <PageHeader title="Reports" description="Operational reports with filters, paging and CSV or Excel export. Every report only contains records you may already see." />
      {q.isError ? <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>
        : q.isPending ? <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-36 w-full" />)}</div>
        : q.data.length === 0 ? <Card><EmptyState title="No reports available" description="Your roles do not include access to any report's records." /></Card> : (
          <ul className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid="report-catalogue">
            {q.data.map((r) => (
              <li key={r.key}>
                <Card className="h-full">
                  <CardHeader title={<Link to={`/reports/${r.key}`} className="hover:underline">{r.title}</Link>} description={r.description} />
                  <CardBody className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted">
                    <span className="inline-flex items-center gap-1"><FileText aria-hidden className="h-3.5 w-3.5" /> {r.columns?.length} columns</span>
                    {r.personalAvailable && <span className="inline-flex items-center gap-1"><ShieldCheck aria-hidden className="h-3.5 w-3.5" /> Personal columns on request (audited)</span>}
                  </CardBody>
                </Card>
              </li>
            ))}
          </ul>
        )}
    </>
  );
}

export default ReportsPage;
