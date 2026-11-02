import { zodResolver } from "@hookform/resolvers/zod";
import { ApiError, unwrap, type OrgUnitType, type TenantSettings } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, CardHeader, ErrorState, Field, Input, PageHeader, Select, Skeleton, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { UNIT_TYPES } from "./orgModel";

const LABEL = z.string().trim().min(1, "Required").max(40, "At most 40 characters")
  .regex(/^[\p{L}\p{N} &'()./-]+$/u, "Letters, digits, spaces and & ' ( ) . / - only");
const schema = z.object({
  labels: z.object(Object.fromEntries(UNIT_TYPES.map((t) => [t, z.object({ singular: LABEL, plural: LABEL })])) as Record<OrgUnitType, z.ZodObject<{ singular: typeof LABEL; plural: typeof LABEL }>>),
  timeZone: z.string().min(1, "Required"),
});
type FormValues = z.infer<typeof schema>;

const STABLE_NAMES: Record<OrgUnitType, string> = { UNIVERSITY: "University", COLLEGE: "College", CAMPUS: "Campus", DEPARTMENT: "Department" };

/** Organization-wide settings: terminology for the stable unit types and the organization's time zone. */
export function SystemSettingsPage() {
  const api = useApi();
  const q = useQuery({ queryKey: ["tenant-settings"], queryFn: async () => unwrap(await api.GET("/api/v1/tenant/settings")) });
  return (
    <>
      <PageHeader title="System Settings" description="Terminology and regional settings for your organization." />
      {q.isPending ? (
        <Card><CardBody className="space-y-4">{Array.from({ length: 5 }, (_, i) => <Skeleton key={i} className="h-10 w-full" />)}</CardBody></Card>
      ) : q.isError ? (
        <Card><ErrorState message={errorInfo(q.error).message} onRetry={() => void q.refetch()} /></Card>
      ) : (
        <SettingsForm key={q.data.version} settings={q.data} />
      )}
    </>
  );
}

function SettingsForm({ settings }: { settings: TenantSettings }) {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { access } = useSession();
  const canEdit = access.has("tenant.settings.manage");
  const [problem, setProblem] = useState<string | null>(null);
  const zones = useMemo(() => {
    const list = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];
    return list.includes(settings.timeZone ?? "") ? list : [settings.timeZone ?? "Asia/Kolkata", ...list];
  }, [settings.timeZone]);
  const stored = (settings.labels ?? {}) as Partial<Record<OrgUnitType, { singular?: string; plural?: string }>>;
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      labels: Object.fromEntries(UNIT_TYPES.map((t) => [t, { singular: stored[t]?.singular ?? "", plural: stored[t]?.plural ?? "" }])) as FormValues["labels"],
      timeZone: settings.timeZone ?? "Asia/Kolkata",
    },
  });

  const save = useMutation({
    mutationFn: async (v: FormValues) => unwrap(await api.PUT("/api/v1/tenant/settings", {
      body: { version: settings.version!, labels: v.labels, timeZone: v.timeZone },
    })),
    onSuccess: (d) => {
      qc.setQueryData(["tenant-settings"], d);
      toast.show("success", "Settings saved");
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === "invalid_time_zone") { form.setError("timeZone", { message: e.message }); return; }
      if (e instanceof ApiError && e.code === "concurrent_modification") void qc.invalidateQueries({ queryKey: ["tenant-settings"] });
      setProblem(errorInfo(e).message);
    },
  });

  const err = form.formState.errors;
  return (
    <form noValidate className="space-y-6" onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })}>
      {!canEdit && <Alert tone="info">You can view these settings. Changing them requires the organization administrator role.</Alert>}
      {problem && <Alert tone="danger">{problem}</Alert>}
      <Card>
        <CardHeader title="Organization terminology"
          description="Names shown for each level of your hierarchy. Stored types do not change, so renaming is safe at any time." />
        <CardBody>
          <fieldset disabled={!canEdit} className="space-y-5">
            {UNIT_TYPES.map((t) => (
              <div key={t} className="grid grid-cols-1 gap-3 border-b border-border pb-5 last:border-0 last:pb-0 md:grid-cols-[10rem_1fr_1fr] md:items-start">
                <p className="pt-2 text-sm font-semibold text-fg">{STABLE_NAMES[t]}</p>
                <Field label="Singular" error={err.labels?.[t]?.singular?.message}>
                  {(p) => <Input {...p} data-testid={`label-${t}-singular`} {...form.register(`labels.${t}.singular`)} />}
                </Field>
                <Field label="Plural" error={err.labels?.[t]?.plural?.message}>
                  {(p) => <Input {...p} data-testid={`label-${t}-plural`} {...form.register(`labels.${t}.plural`)} />}
                </Field>
              </div>
            ))}
          </fieldset>
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Regional settings" />
        <CardBody>
          <fieldset disabled={!canEdit} className="max-w-md">
            <Field label="Time zone" error={err.timeZone?.message} hint="Used for schedules and reports.">
              {(p) => <Select {...p} {...form.register("timeZone")}>{zones.map((z) => <option key={z} value={z}>{z}</option>)}</Select>}
            </Field>
          </fieldset>
        </CardBody>
      </Card>
      {canEdit && (
        <div className="flex flex-wrap justify-end gap-3">
          <Button type="button" variant="secondary" disabled={!form.formState.isDirty || save.isPending} onClick={() => { form.reset(); setProblem(null); }}>Discard changes</Button>
          <Button type="submit" loading={save.isPending} disabled={!form.formState.isDirty}>Save settings</Button>
        </div>
      )}
    </form>
  );
}

export default SystemSettingsPage;
