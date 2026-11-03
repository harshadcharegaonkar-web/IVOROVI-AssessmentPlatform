import { zodResolver } from "@hookform/resolvers/zod";
import { unwrap, type Course } from "@ivorovi/api-client";
import { Alert, Breadcrumbs, Button, ButtonLink, Card, CardBody, Field, Input, PageHeader, Select, Skeleton, TextArea, useToast } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { z } from "zod";
import { useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, fieldError, UnitChoices, useIdempotencyKey, useInvalidateAcademics } from "./AcademicsShared";
import { CODE_PATTERN } from "./academicsModel";
import { useProgramOptions } from "./useAcademicOptions";

const schema = z.object({
  code: z.string().trim().min(1, "Required").max(30, "At most 30 characters").regex(CODE_PATTERN, "Letters, digits and . _ / - (start with a letter or digit)"),
  name: z.string().trim().min(1, "Required").max(200, "At most 200 characters"),
  programId: z.string().min(1, "Choose a program"),
  departmentId: z.string().min(1, "Choose a department"),
  specialization: z.string().trim().max(200, "At most 200 characters"),
  totalTerms: z.string().trim().refine((v) => v === "" || (Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 40), "1 to 40, or leave empty"),
  description: z.string().trim().max(1000, "At most 1000 characters"),
});
type Values = z.infer<typeof schema>;

/** Create or edit a course (branch). The program is fixed once created; the department must belong to the program's institution. */
export function CourseEditorPage() {
  const { id } = useParams();
  const api = useApi();
  const existing = useQuery({ queryKey: ["academics", "course", id], enabled: !!id,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/courses/{id}", { params: { path: { id: id! } } })) });
  if (id && existing.isPending) return <Skeleton className="h-96 w-full" />;
  if (id && existing.isError) return <DetailError error={existing.error} what="Course" onRetry={() => void existing.refetch()} />;
  return <CourseForm key={existing.data?.version ?? "new"} course={existing.data} />;
}

function CourseForm({ course }: { course: Course | undefined }) {
  const api = useApi();
  const labels = useOrgLabels();
  const navigate = useNavigate();
  const toast = useToast();
  const [params] = useSearchParams();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const programs = useProgramOptions().filter((p) => (p.status === "ACTIVE" && p.canManage) || p.id === course?.programId);
  const [problem, setProblem] = useState<string | null>(null);
  const editing = !!course;
  const noun = labels.singular("COURSE");
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: course?.code ?? "", name: course?.name ?? "", programId: course?.programId ?? params.get("program") ?? "",
      departmentId: course?.departmentId ?? "", specialization: course?.specialization ?? "",
      totalTerms: course?.totalTerms != null ? String(course.totalTerms) : "", description: course?.description ?? "",
    },
  });
  const programId = useWatch({ control: form.control, name: "programId" });
  const program = programs.find((p) => p.id === programId);

  const save = useMutation({
    mutationFn: async (v: Values) => {
      const body = { code: v.code, name: v.name, departmentId: v.departmentId, ...(v.specialization ? { specialization: v.specialization } : {}),
        ...(v.totalTerms ? { totalTerms: Number(v.totalTerms) } : {}), ...(v.description ? { description: v.description } : {}) };
      if (editing) return unwrap(await api.PATCH("/api/v1/academics/courses/{id}", { params: { path: { id: course.id! } }, body: { ...body, version: course.version! } }));
      return unwrap(await api.POST("/api/v1/academics/courses", { headers: { "Idempotency-Key": key.get() }, body: { ...body, programId: v.programId } }));
    },
    onSuccess: (c) => { invalidate(); toast.show("success", editing ? `${noun} saved` : `${noun} created`, c.name); void navigate(`/academics/courses/${c.id}`); },
    onError: (e) => {
      key.rotate();
      setProblem(fieldError(e, { code_in_use: "code", invalid_code: "code", invalid_department: "departmentId", inactive_department: "departmentId",
        department_outside_institution: "departmentId", invalid_total_terms: "totalTerms", terms_in_use: "totalTerms", program_inactive: "programId" },
        (f, m) => form.setError(f as keyof Values, { message: m })));
    },
  });
  const err = form.formState.errors;
  const back = editing ? `/academics/courses/${course.id}` : "/academics/courses";
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: labels.plural("COURSE"), to: "/academics/courses" },
        ...(editing ? [{ label: course.name ?? "", to: back }] : []), { label: editing ? "Edit" : `New ${noun.toLowerCase()}` }]} /></div>
      <PageHeader title={editing ? `Edit ${course.name}` : `New ${noun.toLowerCase()}`}
        description={`A ${noun.toLowerCase()} is a branch or specialization of a ${labels.singular("PROGRAM").toLowerCase()}, run by one ${labels.singular("DEPARTMENT").toLowerCase()}.`} />
      <form noValidate onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })} className="space-y-6">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Card><CardBody className="grid grid-cols-1 gap-5 md:grid-cols-2">
          <Field label={labels.singular("PROGRAM")} error={err.programId?.message} hint={editing ? "The program cannot change." : undefined}>
            {(p) => <Select {...p} disabled={editing} {...form.register("programId")}>
              {editing ? <option value={course.programId}>{course.programName}</option> : <><option value="">Choose…</option>
                {programs.map((o) => <option key={o.id} value={o.id}>{o.name} ({o.code}) · {o.ownerName}</option>)}</>}
            </Select>}
          </Field>
          <Field label={labels.singular("DEPARTMENT")} error={err.departmentId?.message}
            hint={program ? `A ${labels.singular("DEPARTMENT").toLowerCase()} of ${program.ownerName}.` : `Choose the ${labels.singular("PROGRAM").toLowerCase()} first.`}>
            {(p) => <Select {...p} {...form.register("departmentId")}><option value="">Choose…</option>
              {(program || editing) && <UnitChoices permission="program.manage" types={["DEPARTMENT"]} within={program?.ownerOrgUnitId ?? null} />}</Select>}
          </Field>
          <Field label="Code" error={err.code?.message} hint={`Unique within the ${labels.singular("PROGRAM").toLowerCase()}, e.g. CSE.`}>
            {(p) => <Input {...p} autoComplete="off" {...form.register("code")} />}
          </Field>
          <Field label="Name" error={err.name?.message}>{(p) => <Input {...p} {...form.register("name")} />}</Field>
          <Field label="Specialization (optional)" error={err.specialization?.message}>{(p) => <Input {...p} {...form.register("specialization")} />}</Field>
          <Field label={`${labels.plural("TERM")} (optional)`} error={err.totalTerms?.message}
            hint={program || editing ? `Leave empty to use the ${labels.singular("PROGRAM").toLowerCase()}'s ${program?.totalTerms ?? course?.effectiveTerms}.` : undefined}>
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

export default CourseEditorPage;
