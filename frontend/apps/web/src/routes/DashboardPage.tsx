import { unwrap } from "@ivorovi/api-client";
import { Badge, Card, CardBody, CardHeader, ErrorState, PageHeader, Skeleton } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { errorInfo, useApi } from "../api/ApiProvider";
import { humanize } from "../lib/format";
import { useSession } from "../session/SessionProvider";

export function DashboardPage() {
  const api = useApi();
  const { me, access } = useSession();
  const features = useQuery({ queryKey: ["features", "effective", null], queryFn: async () => unwrap(await api.GET("/api/v1/features")) });
  const institutions = useQuery({
    queryKey: ["institutions", { size: 1 }],
    enabled: access.hasAnywhere("institution.read"),
    queryFn: async () => unwrap(await api.GET("/api/v1/institutions", { params: { query: { size: 1 } } })),
  });

  const modules = features.data?.filter((f) => f.category === "MODULE") ?? [];
  const ai = features.data?.filter((f) => f.category === "AI") ?? [];

  return (
    <>
      <PageHeader title={`Welcome, ${me.displayName?.split(" ")[0] ?? ""}`} description={me.tenant?.name} />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        <Card>
          <CardHeader title="Your roles" description="Roles assigned to you in this organization" />
          <CardBody className="flex flex-wrap gap-2">
            {(me.roles ?? []).length === 0 ? <p className="text-sm text-muted">No roles assigned yet.</p> :
              me.roles!.map((r) => <Badge key={r} tone="info">{humanize(r)}</Badge>)}
            <p className="w-full pt-2 text-sm"><Link className="text-primary underline" to="/me">View detailed access</Link></p>
          </CardBody>
        </Card>

        {access.hasAnywhere("institution.read") && (
          <Card>
            <CardHeader title="Institutions" description="Visible to you" />
            <CardBody>
              {institutions.isPending ? <Skeleton className="h-8 w-16" /> : institutions.isError ? (
                <ErrorState message={errorInfo(institutions.error).message} onRetry={() => void institutions.refetch()} />
              ) : (
                <p className="text-3xl font-semibold" data-testid="institution-count">{institutions.data.totalElements}</p>
              )}
              <p className="pt-2 text-sm"><Link className="text-primary underline" to="/institutions">Browse institutions</Link></p>
            </CardBody>
          </Card>
        )}

        <Card>
          <CardHeader title="Modules" description="Enabled for your organization" />
          <CardBody>
            {features.isPending ? <Skeleton className="h-16 w-full" /> : features.isError ? (
              <ErrorState message={errorInfo(features.error).message} onRetry={() => void features.refetch()} />
            ) : (
              <ul className="space-y-2 text-sm">
                {modules.map((m) => (
                  <li key={m.key} className="flex items-center justify-between gap-2">
                    <span>{humanize(m.key?.replace("module.", ""))}</span>
                    <Badge tone={m.enabled ? "success" : "neutral"}>{m.enabled ? "Enabled" : "Disabled"}</Badge>
                  </li>
                ))}
                <li className="flex items-center justify-between gap-2 border-t border-border pt-2">
                  <span>AI capabilities</span>
                  <Badge tone={ai.some((a) => a.enabled) ? "warning" : "neutral"}>
                    {ai.filter((a) => a.enabled).length} of {ai.length} enabled
                  </Badge>
                </li>
              </ul>
            )}
          </CardBody>
        </Card>
      </div>
    </>
  );
}
