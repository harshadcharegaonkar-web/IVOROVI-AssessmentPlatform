import { unwrap, type FeatureConfigurationView } from "@ivorovi/api-client";
import { Badge, Button, Card, CardHeader, ConfirmDialog, ErrorState, Skeleton, Switch, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { describeSource, groupByCategory, nextActionFor } from "./featureModel";

interface Props { institutionId: string | null; institutionName?: string }

export function FeatureConfigurationPanel({ institutionId, institutionName }: Props) {
  const api = useApi();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { access } = useSession();
  const canManage = access.hasInInstitution("feature.manage", institutionId);
  const [pending, setPending] = useState<{ flag: FeatureConfigurationView; enabled: boolean } | null>(null);

  const query = useQuery({
    queryKey: ["features", "configuration", institutionId],
    queryFn: async () => unwrap(await api.GET("/api/v1/features/configuration", {
      params: { query: institutionId ? { institutionId } : {} },
    })),
  });

  const setOverride = useMutation({
    mutationFn: async ({ key, enabled }: { key: string; enabled: boolean }) => unwrap(await api.PUT("/api/v1/features/{key}/override", {
      params: { path: { key } }, body: { enabled, ...(institutionId ? { institutionId } : {}) },
    })),
    onSuccess: (result) => {
      toast.show("success", "Feature updated", `${result.key} is now ${result.enabled ? "enabled" : "disabled"}.`);
      void queryClient.invalidateQueries({ queryKey: ["features"] });
    },
    onError: (e) => toast.show("danger", "Change not saved", errorInfo(e).message),
    onSettled: () => setPending(null),
  });

  const clearOverride = useMutation({
    mutationFn: async (key: string) => unwrap(await api.DELETE("/api/v1/features/{key}/override", {
      params: { path: { key }, query: institutionId ? { institutionId } : {} },
    })),
    onSuccess: (result) => {
      toast.show("success", "Override removed", `${result.key} now inherits its value.`);
      void queryClient.invalidateQueries({ queryKey: ["features"] });
    },
    onError: (e) => toast.show("danger", "Change not saved", errorInfo(e).message),
  });

  const request = (flag: FeatureConfigurationView, enabled: boolean) => {
    if (nextActionFor(flag, enabled) === "confirm") setPending({ flag, enabled });
    else setOverride.mutate({ key: flag.key!, enabled });
  };

  const scopeLabel = institutionId ? institutionName ?? "this institution" : "the whole organization";

  return (
    <Card>
      <CardHeader
        title={institutionId ? "Institution features" : "Organization features"}
        description={canManage ? `Changes apply to ${scopeLabel} and are recorded in the audit log.` : "You can view this configuration but not change it."}
      />
      {query.isPending ? (
        <div className="space-y-3 p-5">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-10 w-full" />)}</div>
      ) : query.isError ? (
        <ErrorState message={errorInfo(query.error).message} traceId={errorInfo(query.error).traceId} onRetry={() => void query.refetch()} />
      ) : (
        groupByCategory(query.data).map(({ category, title, flags }) => (
          <section key={category} aria-labelledby={`cat-${category}`} className="border-t border-border first:border-t-0">
            <h3 id={`cat-${category}`} className="bg-surface-muted px-5 py-2 text-xs font-semibold uppercase tracking-wide text-muted">{title}</h3>
            <ul>
              {flags.map((f) => {
                const own = institutionId ? f.institutionOverride : f.tenantOverride;
                const blockedByDeployment = !!f.deploymentDisabled;
                const institutionLocked = !!institutionId && !f.institutionOverridable;
                const disabled = !canManage || blockedByDeployment || institutionLocked || setOverride.isPending;
                return (
                  <li key={f.key} className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-border px-5 py-3 first:border-t-0" data-testid={`feature-${f.key}`}>
                    <div className="min-w-0 flex-1 basis-64">
                      <p className="font-medium text-fg">{f.description}</p>
                      <p className="text-xs text-muted"><code>{f.key}</code> · {describeSource(f)}</p>
                    </div>
                    <div className="flex items-center gap-3">
                      {blockedByDeployment && <Badge tone="warning">Disabled by deployment</Badge>}
                      {institutionLocked && <Badge>Organization-level only</Badge>}
                      <Badge tone={f.enabled ? "success" : "neutral"}>{f.enabled ? "On" : "Off"}</Badge>
                      {own !== undefined && own !== null && canManage && (
                        <Button variant="ghost" size="sm" onClick={() => clearOverride.mutate(f.key!)} loading={clearOverride.isPending && clearOverride.variables === f.key}>
                          Reset
                        </Button>
                      )}
                      <Switch
                        label={`${f.description} for ${scopeLabel}`}
                        checked={!!f.enabled}
                        disabled={disabled}
                        onCheckedChange={(v) => request(f, v)}
                      />
                    </div>
                  </li>
                );
              })}
            </ul>
          </section>
        ))
      )}
      <ConfirmDialog
        open={!!pending}
        onOpenChange={(o) => { if (!o) setPending(null); }}
        title={`Enable ${pending?.flag.description ?? "AI feature"}?`}
        description={
          <>
            <p>AI output in Ivorovi is advisory: scores, flags and recommendations require approval by an authorised person before they take effect.</p>
            <p className="mt-2">Make sure your organization has approved the configured AI provider for processing assessment data.</p>
          </>
        }
        confirmLabel="Enable"
        busy={setOverride.isPending}
        onConfirm={() => pending && setOverride.mutate({ key: pending.flag.key!, enabled: pending.enabled })}
      />
    </Card>
  );
}
