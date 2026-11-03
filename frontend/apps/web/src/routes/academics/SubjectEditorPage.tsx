import { zodResolver } from "@hookform/resolvers/zod";
import { unwrap, type Subject } from "@ivorovi/api-client";
import { Alert, Breadcrumbs, Button, ButtonLink, Card, CardBody, Field, Input, PageHeader, Select, Skeleton, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate, useParams } from "react-router";
import { z } from "zod";
import { useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, fieldError, UnitChoices, useIdempotencyKey, useInvalidateAcademics } from "./AcademicsShared";
import { CATEGORIES, CODE_PATTERN, DELIVERY, type Category, type Delivery } from "./academicsModel";

const schema = z.object({
  code: z.string().trim().min(1, "Required").max(30, "At most 30 characters").regex(CODE_PATTERN, "Letters, digits and . _ / - (start with a letter or digit)"),
  name: z.string().trim().min(1, "Required").max(200, "At most 200 characters"),
  description: z.string().trim().max(2000, "At most 2000 characters"),
  credits: z.string().trim().refine((v) => /^\d+(\.\d)?$/.test(v) && Number(v) <= 40, "0 to 40 (one decimal)"),
  delivery: z.string().min(1, "Choose one"),
  category: z.string().min(1, "Choose one"),
  elective: z.enum(["yes", "no"]),
  ownerOrgUnitId: z.string().min(1, "Choose who owns it"),
});
type Values = z.infer<typeof schema>;

/** Create or edit a subject master. */
export function SubjectEditorPage() {
  const { id } = useParams();
  const api = useApi();
  const existing = useQuery({ queryKey: ["academics", "subject", id], enabled: !!id,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/subjects/{id}", { params: { path: { id: id! } } })) });
  if (id && existing.isPending) return <Skeleton className="h-96 w-full" />;
  if (id && existing.isError) return <DetailError error={existing.error} what="Subject" onRetry={() => void existing.refetch()} />;
  return <SubjectForm key={existing.data?.subject?.version ?? "new"} subject={existing.data?.subject} />;
}

function SubjectForm({ subject }: { subject: Subject | undefined }) {
  const api = useApi();
  const labels = useOrgLabels();
  const navigate = useNavigate();
  const toast = useToast();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const [problem, setProblem] = useState<string | null>(null);
  const editing = !!subject;
  const noun = labels.singular("SUBJECT");
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: subject?.code ?? "", name: subject?.name ?? "", description: subject?.description ?? "",
      credits: subject?.credits != null ? String(subject.credits) : "4", delivery: subject?.delivery ?? "THEORY", category: subject?.category ?? "CORE",
      elective: subject?.elective ? "yes" : "no", ownerOrgUnitId: subject?.ownerOrgUnitId ?? "",
    },
  });
  const save = useMutation({
    mutationFn: async (v: Values) => {
      const body = { code: v.code, name: v.name, credits: Number(v.credits), delivery: v.delivery as Delivery, category: v.category as Category,
        elective: v.elective === "yes", ...(v.description ? { description: v.description } : {}) };
      if (editing) return unwrap(await api.PATCH("/api/v1/academics/subjects/{id}", { params: { path: { id: subject.id! } }, body: { ...body, version: subject.version! } }));
      return unwrap(await api.POST("/api/v1/academics/subjects", { headers: { "Idempotency-Key": key.get() }, body: { ...body, ownerOrgUnitId: v.ownerOrgUnitId } }));
    },
    onSuccess: (d) => { invalidate(); toast.show("success", editing ? `${noun} saved` : `${noun} created`, d.subject?.name); void navigate(`/academics/subjects/${d.subject?.id}`); },
    onError: (e) => {
      key.rotate();
      setProblem(fieldError(e, { code_in_use: "code", invalid_code: "code", invalid_credits: "credits", invalid_owner: "ownerOrgUnitId", inactive_owner: "ownerOrgUnitId" },
        (f, m) => form.setError(f as keyof Values, { message: m })));
    },
  });
  const err = form.formState.errors;
  const back = editing ? `/academics/subjects/${subject.id}` : "/academics/subjects";
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: labels.plural("SUBJECT"), to: "/academics/subjects" },
        ...(editing ? [{ label: subject.code ?? "", to: back }] : []), { label: editing ? "Edit" : `New ${noun.toLowerCase()}` }]} /></div>
      <PageHeader title={editing ? `Edit ${subject.code} · ${subject.name}` : `New ${noun.toLowerCase()}`}
        description={`A ${noun.toLowerCase()} owned by a unit can be used by every ${labels.singular("COURSE").toLowerCase()} of that institution.`} />
      <form noValidate onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })} className="space-y-6">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Card><CardBody className="grid grid-cols-1 gap-5 md:grid-cols-2">
          <Field label="Code" error={err.code?.message} hint="Unique within the owner, e.g. CS101.">{(p) => <Input {...p} autoComplete="off" {...form.register("code")} />}</Field>
          <Field label="Name" error={err.name?.message}>{(p) => <Input {...p} {...form.register("name")} />}</Field>
          <Field label="Credits" error={err.credits?.message}>{(p) => <Input {...p} inputMode="decimal" {...form.register("credits")} />}</Field>
          <Field label="Theory or practical" error={err.delivery?.message}>
            {(p) => <Select {...p} {...form.register("delivery")}>{DELIVERY.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}</Select>}
          </Field>
          <Field label="Category" error={err.category?.message}>
            {(p) => <Select {...p} {...form.register("category")}>{CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}</Select>}
          </Field>
          <Field label="Elective or mandatory" error={err.elective?.message} hint="The default when it is added to a curriculum.">
            {(p) => <Select {...p} {...form.register("elective")}><option value="no">Mandatory</option><option value="yes">Elective</option></Select>}
          </Field>
          <Field label="Owned by" error={err.ownerOrgUnitId?.message} hint={editing ? "The owner cannot change." : "Where you manage subjects."}>
            {(p) => <Select {...p} disabled={editing} {...form.register("ownerOrgUnitId")}>
              {editing ? <option value={subject.ownerOrgUnitId}>{subject.ownerName}</option> : <><option value="">Choose…</option>
                <UnitChoices permission="subject.manage" types={["UNIVERSITY", "COLLEGE", "CAMPUS", "DEPARTMENT"]} /></>}
            </Select>}
          </Field>
          <div className="md:col-span-2">
            <Field label="Description (optional)" error={err.description?.message}>{(p) => <TextArea {...p} rows={4} {...form.register("description")} />}</Field>
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

export default SubjectEditorPage;
