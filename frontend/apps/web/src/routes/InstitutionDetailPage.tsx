import { ApiError, unwrap } from "@ivorovi/api-client";
import { Badge, Card, CardBody, CardHeader, ErrorState, PageHeader, Skeleton } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { ChevronLeft } from "lucide-react";
import { Link, useParams } from "react-router";
import { errorInfo, useApi } from "../api/ApiProvider";
import { formatDateTime } from "../lib/format";
import { useSession } from "../session/SessionProvider";
import { FeatureConfigurationPanel } from "./features/FeatureConfigurationPanel";

export function InstitutionDetailPage() {
  const { id = "" } = useParams();
  const api = useApi();
  const { access } = useSession();
  const query = useQuery({
    queryKey: ["institution", id],
    queryFn: async () => unwrap(await api.GET("/api/v1/institutions/{id}", { params: { path: { id } } })),
  });

  const back = <Link to="/institutions" className="mb-4 inline-flex items-center gap-1 text-sm text-muted-strong hover:text-fg xl:hidden"><ChevronLeft aria-hidden className="h-4 w-4" /> All institutions</Link>;

  if (query.isPending) return <>{back}<Skeleton className="h-8 w-64" /></>;
  if (query.isError) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return <>{back}<ErrorState title={notFound ? "Institution not found" : "Could not load institution"}
      message={notFound ? "It may not exist, or it may belong to another organization." : errorInfo(query.error).message}
      {...(notFound ? {} : { onRetry: () => void query.refetch() })} /></>;
  }
  const inst = query.data;
  return (
    <>
      {back}
      <PageHeader title={inst.name ?? ""} description={<span className="inline-flex items-center gap-2"><code>{inst.code}</code><Badge tone={inst.status === "ACTIVE" ? "success" : "neutral"}>{inst.status}</Badge></span>} />
      <div className="space-y-6">
        <Card>
          <CardHeader title="Details" />
          <CardBody>
            <dl className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-3">
              <div><dt className="text-muted">Code</dt><dd className="font-medium">{inst.code}</dd></div>
              <div><dt className="text-muted">Status</dt><dd className="font-medium">{inst.status}</dd></div>
              <div><dt className="text-muted">Created</dt><dd className="font-medium">{formatDateTime(inst.createdAt)}</dd></div>
            </dl>
          </CardBody>
        </Card>
        {access.hasInInstitution("feature.read", id) && <FeatureConfigurationPanel institutionId={id} institutionName={inst.name ?? ""} />}
      </div>
    </>
  );
}
export default InstitutionDetailPage;
