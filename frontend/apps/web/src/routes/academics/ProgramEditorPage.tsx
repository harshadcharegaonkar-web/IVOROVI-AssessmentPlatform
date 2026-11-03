import { zodResolver } from "@hookform/resolvers/zod";
import { unwrap, type Program } from "@ivorovi/api-client";
import { Alert, Breadcrumbs, Button, ButtonLink, Card, CardBody, Field, Input, PageHeader, Select, Skeleton, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { useNavigate, useParams } from "react-router";
import { z } from "zod";
import { useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, fieldError, UnitChoices, useIdempotencyKey, useInvalidateAcademics } from "./AcademicsShared";
import { CODE_PATTERN, defaultTotalTerms, QUALIFICATION_LEVELS, TERM_SYSTEMS, type QualificationLevel, type TermSystem } from "./academicsModel";

const schema = z.object({
  code: z.string().trim().min(1, "Required").max(30, "At most 30 characters").regex(CODE_PATTERN, "Letters, digits and . _ / - (start with a letter or digit)"),
  name: z.string().trim().min(1, "Required").max(200, "At most 200 characters"),
  qualificationLevel: z.string().min(1, "Choose a level"),
  awardedQualification: z.string().trim().max(200, "At most 200 characters"),
  ownerOrgUnitId: z.string().min(1, "Choose who owns it"),
  termSystem: z.string().min(1, "Choose a term system"),
  termsPerYear: z.string(),
  durationYears: z.string().trim().refine((v) => Number(v) > 0 && Number(v) <= 10 && /^\d+(\.\d)?$/.test(v), "0.5 to 10 years (one decimal)"),
  totalTerms: z.string().trim().refine((v) => v === "" || (Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 40), "1 to 40, or leave empty"),
  description: z.string().trim().max(1000, "At most 1000 characters"),
}).superRefine((v, ctx) => {
  if (v.termSystem === "OTHER" && !(Number(v.termsPerYear) >= 1 && Number(v.termsPerYear) <= 6)) {
    ctx.addIssue({ code: "custom", path: ["termsPerYear"], message: "1 to 6 terms per year" });
  }
});
type Values = z.infer<typeof schema>;

/** Create or edit a program. The owner (university or college) is fixed once created. */
export function ProgramEditorPage() {
  const { id } = useParams();
  const api = useApi();
  const existing = useQuery({ queryKey: ["academics", "program", id], enabled: !!id,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/programs/{id}", { params: { path: { id: id! } } })) });
  if (id && existing.isPending) return <Skeleton className="h-96 w-full" />;
  if (id && existing.isError) return <DetailError error={existing.error} what="Program" onRetry={() => void existing.refetch()} />;
  return <ProgramForm key={existing.data?.version ?? "new"} program={existing.data} />;
}

function ProgramForm({ program }: { program: Program | undefined }) {
  const api = useApi();
  const labels = useOrgLabels();
  const navigate = useNavigate();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const [problem, setProblem] = useState<string | null>(null);
  const editing = !!program;
  const noun = labels.singular("PROGRAM");
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: program?.code ?? "", name: program?.name ?? "", qualificationLevel: program?.qualificationLevel ?? "UNDERGRADUATE",
      awardedQualification: program?.awardedQualification ?? "", ownerOrgUnitId: program?.ownerOrgUnitId ?? "",
      termSystem: program?.termSystem ?? "SEMESTER", termsPerYear: program?.termSystem === "OTHER" ? String(program.termsPerYear ?? "") : "",
      durationYears: program?.durationYears != null ? String(program.durationYears) : "4",
      totalTerms: program?.totalTerms != null ? String(program.totalTerms) : "", description: program?.description ?? "",
    },
  });
  const [system, duration, perYear] = useWatch({ control: form.control, name: ["termSystem", "durationYears", "termsPerYear"] });
  const computed = defaultTotalTerms(system as TermSystem, Number(duration), Number(perYear) || null);

  const save = useMutation({
    mutationFn: async (v: Values) => {
      const body = {
        code: v.code, name: v.name, qualificationLevel: v.qualificationLevel as QualificationLevel, termSystem: v.termSystem as TermSystem,
        durationYears: Number(v.durationYears), ...(v.awardedQualification ? { awardedQualification: v.awardedQualification } : {}),
        ...(v.termSystem === "OTHER" ? { termsPerYear: Number(v.termsPerYear) } : {}), ...(v.totalTerms ? { totalTerms: Number(v.totalTerms) } : {}),
        ...(v.description ? { description: v.description } : {}),
      };
      if (editing) return unwrap(await api.PATCH("/api/v1/academics/programs/{id}", { params: { path: { id: program.id! } }, body: { ...body, version: program.version! } }));
      return unwrap(await api.POST("/api/v1/academics/programs", { headers: { "Idempotency-Key": key.get() }, body: { ...body, ownerOrgUnitId: v.ownerOrgUnitId } }));
    },
    onSuccess: (p) => { invalidate(); toast.show("success", editing ? `${noun} saved` : `${noun} created`, p.name); void navigate(`/academics/programs/${p.id}`); },
    onError: (e) => {
      key.rotate();
      setProblem(fieldError(e, { code_in_use: "code", invalid_code: "code", invalid_owner: "ownerOrgUnitId", inactive_owner: "ownerOrgUnitId",
        invalid_total_terms: "totalTerms", terms_in_use: "totalTerms", invalid_terms_per_year: "termsPerYear", invalid_duration: "durationYears" },
        (f, m) => form.setError(f as keyof Values, { message: m })));
    },
  });
  const err = form.formState.errors;
  const back = editing ? `/academics/programs/${program.id}` : "/academics/programs";
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: labels.plural("PROGRAM"), to: "/academics/programs" },
        ...(editing ? [{ label: program.name ?? "", to: back }] : []), { label: editing ? "Edit" : `New ${noun.toLowerCase()}` }]} /></div>
      <PageHeader title={editing ? `Edit ${program.name}` : `New ${noun.toLowerCase()}`}
        description={`A ${noun.toLowerCase()} is a qualification such as a bachelor's degree. Its length decides how many terms its ${labels.plural("COURSE").toLowerCase()} can use.`} />
      <form noValidate onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })} className="space-y-6">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Card><CardBody className="grid grid-cols-1 gap-5 md:grid-cols-2">
          <Field label="Code" error={err.code?.message} hint="Unique within the owner, e.g. BTECH.">
            {(p) => <Input {...p} autoComplete="off" {...form.register("code")} />}
          </Field>
          <Field label="Name" error={err.name?.message}>{(p) => <Input {...p} {...form.register("name")} />}</Field>
          <Field label="Qualification level" error={err.qualificationLevel?.message}>
            {(p) => <Select {...p} {...form.register("qualificationLevel")}>{QUALIFICATION_LEVELS.map((l) => <option key={l.value} value={l.value}>{l.label}</option>)}</Select>}
          </Field>
          <Field label="Awarded qualification (optional)" error={err.awardedQualification?.message} hint="For example B.Tech or MBA.">
            {(p) => <Input {...p} {...form.register("awardedQualification")} />}
          </Field>
          <Field label="Owned by" error={err.ownerOrgUnitId?.message} hint={editing ? "The owner cannot change." : `A ${labels.singular("UNIVERSITY").toLowerCase()} or ${labels.singular("COLLEGE").toLowerCase()} where you manage programs.`}>
            {(p) => <Select {...p} disabled={editing} {...form.register("ownerOrgUnitId")}>
              {editing ? <option value={program.ownerOrgUnitId}>{program.ownerName}</option> : <><option value="">Choose…</option>
                <UnitChoices permission="program.manage" types={["UNIVERSITY", "COLLEGE"]} /></>}
            </Select>}
          </Field>
          <Field label="Duration (years)" error={err.durationYears?.message}>
            {(p) => <Input {...p} inputMode="decimal" {...form.register("durationYears")} />}
          </Field>
          <Field label="Term system" error={err.termSystem?.message} hint={`Any number of terms per year is supported; ${labels.plural("TERM").toLowerCase()} are named in the calendar.`}>
            {(p) => <Select {...p} {...form.register("termSystem")}>{TERM_SYSTEMS.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}</Select>}
          </Field>
          {system === "OTHER" && (
            <Field label="Terms per year" error={err.termsPerYear?.message}>{(p) => <Input {...p} inputMode="numeric" {...form.register("termsPerYear")} />}</Field>
          )}
          <Field label="Total terms (optional)" error={err.totalTerms?.message}
            hint={computed ? `Leave empty to use ${computed} (duration × terms per year).` : "Leave empty to calculate from the duration."}>
            {(p) => <Input {...p} inputMode="numeric" {...form.register("totalTerms")} />}
          </Field>
          <div className="md:col-span-2">
            <Field label="Description (optional)" error={err.description?.message}>{(p) => <TextArea {...p} rows={3} {...form.register("description")} />}</Field>
          </div>
        </CardBody></Card>
        <div className="flex flex-wrap justify-end gap-3">
          <ButtonLink to={back}>Cancel</ButtonLink>
          <Button type="submit" loading={save.isPending}>{editing ? "Save changes" : `Create ${noun.toLowerCase()}`}</Button>
        </div>
      </form>
    </>
  );
}

export default ProgramEditorPage;
