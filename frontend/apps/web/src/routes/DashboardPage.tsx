import { unwrap } from "@ivorovi/api-client";
import { Badge, ButtonLink, Card, CardBody, CardHeader, EmptyState, ErrorState, KpiCard, Link, PageHeader, Skeleton } from "@ivorovi/ui";
import { useQuery } from "@tanstack/react-query";
import { Bell, Building2, CheckCircle2, ScrollText, ShieldAlert, SlidersHorizontal, Sparkles, UserRound, XCircle } from "lucide-react";
import { errorInfo, useApi } from "../api/ApiProvider";
import { formatNumber, formatRelative, humanize } from "../lib/format";
import { useInstitutionContext } from "../session/InstitutionContext";
import { useSession } from "../session/SessionProvider";

/**
 * Administration dashboard (design system §5). Increment 1.0 shows the metrics that exist today; organisation,
 * people and academic KPIs are added as increments 1.1–1.6 deliver their data. Every value comes from the API.
 */
export function DashboardPage() {
  const api = useApi();
  const { me, access } = useSession();
  const { selectedId, options } = useInstitutionContext();
  const scopeName = options.find((o) => o.id === selectedId)?.name;

  const features = useQuery({
    queryKey: ["features", "effective", selectedId],
    queryFn: async () => unwrap(await api.GET("/api/v1/features", { params: { query: selectedId ? { institutionId: selectedId } : {} } })),
  });
  const institutions = useQuery({
    queryKey: ["institutions", { size: 1 }],
    enabled: access.hasAnywhere("institution.read"),
    queryFn: async () => unwrap(await api.GET("/api/v1/institutions", { params: { query: { size: 1 } } })),
  });
  const notifications = useQuery({
    queryKey: ["notifications"],
    queryFn: async () => unwrap(await api.GET("/api/v1/notifications", { params: { query: { size: 10 } } })),
  });
  const activity = useQuery({
    queryKey: ["audit", "recent"],
    enabled: access.has("audit.read"),
    queryFn: async () => unwrap(await api.GET("/api/v1/audit-events", { params: { query: { size: 6 } } })),
  });

  const modules = features.data?.filter((f) => f.category === "MODULE") ?? [];
  const ai = features.data?.filter((f) => f.category === "AI") ?? [];

  return (
    <>
      <PageHeader
        title="Dashboard"
        description={`${me.tenant?.name ?? ""}${scopeName ? ` · ${scopeName}` : ""}`}
        actions={access.hasAnywhere("feature.read") ? <ButtonLink to="/settings/features"><SlidersHorizontal aria-hidden className="h-4 w-4" /> Feature settings</ButtonLink> : undefined}
      />
      <h2 className="sr-only">Key figures</h2>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4 xl:gap-6">
        {access.hasAnywhere("institution.read") && (
          <KpiCard icon={Building2} label="Institutions" to="/institutions" testId="institution-count"
            loading={institutions.isPending} value={formatNumber(institutions.data?.totalElements)} />
        )}
        <KpiCard icon={CheckCircle2} label="Active modules" loading={features.isPending}
          value={`${modules.filter((m) => m.enabled).length} / ${modules.length}`}
          secondary={modules.filter((m) => m.enabled).map((m) => humanize(m.key?.replace("module.", ""))).join(" · ") || "None enabled"} />
        <KpiCard icon={Sparkles} label="AI capabilities enabled" loading={features.isPending}
          value={`${ai.filter((a) => a.enabled).length} / ${ai.length}`} secondary="Off by default; human approval required" />
        <KpiCard icon={Bell} label="Unread notifications" loading={notifications.isPending} value={formatNumber(notifications.data?.unread)} />
      </div>

      <div className="mt-6 grid grid-cols-1 gap-6 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader title="Recent activity" description="Latest audited operations in your organization"
            actions={access.has("audit.read") ? <Link to="/audit" className="text-sm font-medium text-primary hover:underline">View all</Link> : undefined} />
          {!access.has("audit.read") ? (
            <EmptyState title="Activity is visible to administrators" description="Your role does not include access to the audit trail." />
          ) : activity.isPending ? (
            <div className="space-y-4 p-6">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-10 w-full" />)}</div>
          ) : activity.isError ? (
            <ErrorState message={errorInfo(activity.error).message} onRetry={() => void activity.refetch()} />
          ) : (activity.data.items ?? []).length === 0 ? (
            <EmptyState title="No activity yet" />
          ) : (
            <ul className="divide-y divide-border">
              {activity.data.items!.map((e) => {
                const denied = e.outcome !== "SUCCESS";
                const Icon = denied ? (e.outcome === "DENIED" ? ShieldAlert : XCircle) : ScrollText;
                return (
                  <li key={e.id} className="flex items-start gap-3 px-5 py-3.5 sm:px-6">
                    <span aria-hidden className={denied ? "flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-danger-soft text-danger" : "flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary-soft text-primary"}>
                      <Icon className="h-4 w-4" />
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-semibold text-fg">{humanize(e.action)}</p>
                      <p className="truncate text-sm text-muted">
                        {[e.resourceType ? `${e.resourceType}: ${e.resourceId}` : null,
                          ...Object.entries(e.details ?? {}).slice(0, 2).map(([k, v]) => `${k}=${v}`)].filter(Boolean).join(" · ") || "—"}
                      </p>
                    </div>
                    <div className="shrink-0 text-right">
                      <p className="text-xs text-muted">{formatRelative(e.occurredAt)}</p>
                      {denied && <Badge tone="danger" className="mt-1">{e.outcome}</Badge>}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>

        <div className="min-w-0 space-y-6">
          <Card>
            <CardHeader title="Quick actions" />
            <CardBody className="grid grid-cols-1 gap-2">
              {access.hasAnywhere("institution.read") && <ButtonLink to="/institutions" className="w-full justify-start"><Building2 aria-hidden className="h-4 w-4" /> Browse institutions</ButtonLink>}
              {access.hasAnywhere("feature.manage") && <ButtonLink to="/settings/features" className="w-full justify-start"><SlidersHorizontal aria-hidden className="h-4 w-4" /> Configure features</ButtonLink>}
              {access.has("audit.read") && <ButtonLink to="/audit" className="w-full justify-start"><ScrollText aria-hidden className="h-4 w-4" /> Review audit log</ButtonLink>}
              <ButtonLink to="/me" className="w-full justify-start"><UserRound aria-hidden className="h-4 w-4" /> View my access</ButtonLink>
            </CardBody>
          </Card>
          <Card>
            <CardHeader title="Your roles" />
            <CardBody className="flex flex-wrap gap-2">
              {(me.roles ?? []).length === 0 ? <p className="text-sm text-muted">No roles assigned yet.</p>
                : me.roles!.map((r) => <Badge key={r} tone="info">{humanize(r)}</Badge>)}
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  );
}

export default DashboardPage;
