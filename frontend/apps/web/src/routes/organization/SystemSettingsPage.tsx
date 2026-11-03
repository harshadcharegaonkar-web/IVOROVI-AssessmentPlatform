import { zodResolver } from "@hookform/resolvers/zod";
import { ApiError, unwrap, type TenantSettings } from "@ivorovi/api-client";
import { Alert, Button, Card, CardBody, CardHeader, ErrorState, Field, Input, PageHeader, Select, Skeleton, Switch, useToast } from "@ivorovi/ui";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { z } from "zod";
import { errorInfo, useApi } from "../../api/ApiProvider";
import { useSession } from "../../session/SessionProvider";
import { TERMINOLOGY_DEFAULTS, type AcademicTermKey, type TerminologyKey } from "../../session/useOrgLabels";
import { UNIT_TYPES } from "./orgModel";

const ACADEMIC_TERMS: AcademicTermKey[] = ["PROGRAM", "COURSE", "SUBJECT", "TERM", "BATCH"];
const ALL_TERMS: TerminologyKey[] = [...UNIT_TYPES, ...ACADEMIC_TERMS];

const LABEL = z.string().trim().min(1, "Required").max(40, "At most 40 characters")
  .regex(/^[\p{L}\p{N} &'()./-]+$/u, "Letters, digits, spaces and & ' ( ) . / - only");
const schema = z.object({
  labels: z.object(Object.fromEntries(ALL_TERMS.map((t) => [t, z.object({ singular: LABEL, plural: LABEL })])) as Record<TerminologyKey, z.ZodObject<{ singular: typeof LABEL; plural: typeof LABEL }>>),
  timeZone: z.string().min(1, "Required"),
  allowOverlappingAcademicYears: z.boolean(),
});
type FormValues = z.infer<typeof schema>;

/** Stable internal names shown next to the editable labels (the default singular of each type). */
const STABLE_NAMES = (t: TerminologyKey) => t === "TERM" ? "Term (semester)" : TERMINOLOGY_DEFAULTS[t].singular;

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
  const stored = (settings.labels ?? {}) as Partial<Record<TerminologyKey, { singular?: string; plural?: string }>>;
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      labels: Object.fromEntries(ALL_TERMS.map((t) => [t, { singular: stored[t]?.singular ?? TERMINOLOGY_DEFAULTS[t].singular, plural: stored[t]?.plural ?? TERMINOLOGY_DEFAULTS[t].plural }])) as FormValues["labels"],
      timeZone: settings.timeZone ?? "Asia/Kolkata",
      allowOverlappingAcademicYears: settings.allowOverlappingAcademicYears ?? false,
    },
  });

  const save = useMutation({
    mutationFn: async (v: FormValues) => unwrap(await api.PUT("/api/v1/tenant/settings", {
      body: { version: settings.version!, labels: v.labels, timeZone: v.timeZone, allowOverlappingAcademicYears: v.allowOverlappingAcademicYears },
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
  const row = (t: TerminologyKey) => (
    <div key={t} className="grid grid-cols-1 gap-3 border-b border-border pb-5 last:border-0 last:pb-0 md:grid-cols-[10rem_1fr_1fr] md:items-start">
      <p className="pt-2 text-sm font-semibold text-fg">{STABLE_NAMES(t)}</p>
      <Field label="Singular" error={err.labels?.[t]?.singular?.message}>
        {(p) => <Input {...p} data-testid={`label-${t}-singular`} {...form.register(`labels.${t}.singular`)} />}
      </Field>
      <Field label="Plural" error={err.labels?.[t]?.plural?.message}>
        {(p) => <Input {...p} data-testid={`label-${t}-plural`} {...form.register(`labels.${t}.plural`)} />}
      </Field>
    </div>
  );
  return (
    <form noValidate className="space-y-6" onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })}>
      {!canEdit && <Alert tone="info">You can view these settings. Changing them requires the organization administrator role.</Alert>}
      {problem && <Alert tone="danger">{problem}</Alert>}
      <Card>
        <CardHeader title="Organization terminology"
          description="Names shown for each level of your hierarchy. Stored types do not change, so renaming is safe at any time." />
        <CardBody>
          <fieldset disabled={!canEdit} className="space-y-5">
            {UNIT_TYPES.map(row)}
          </fieldset>
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Academic terminology"
          description="Names of the academic levels (Program → Course → Subject), terms and batches. Stored types do not change." />
        <CardBody>
          <fieldset disabled={!canEdit} className="space-y-5">
            {ACADEMIC_TERMS.map(row)}
          </fieldset>
        </CardBody>
      </Card>
      <Card>
        <CardHeader title="Academic calendar" />
        <CardBody>
          <fieldset disabled={!canEdit} className="flex items-start gap-3">
            <Controller control={form.control} name="allowOverlappingAcademicYears" render={({ field }) => (
              <Switch id="overlap" checked={field.value} onCheckedChange={field.onChange} disabled={!canEdit} label="Allow overlapping academic years" />)} />
            <label htmlFor="overlap" className="text-sm">
              <span className="block font-medium text-fg">Allow overlapping academic years</span>
              <span className="block text-muted-strong">When off, academic years of the same owner cannot share dates. Terms within a year never overlap.</span>
            </label>
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
