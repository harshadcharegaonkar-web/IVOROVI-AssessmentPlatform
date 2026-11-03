import { zodResolver } from "@hookform/resolvers/zod";
import { unwrap, type Batch } from "@ivorovi/api-client";
import { Alert, Breadcrumbs, Button, ButtonLink, Card, CardBody, Field, Input, PageHeader, Select, Skeleton, useToast } from "@ivorovi/ui";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { z } from "zod";
import { useApi } from "../../api/ApiProvider";
import { useOrgLabels } from "../../session/useOrgLabels";
import { DetailError, fieldError, useIdempotencyKey, useInvalidateAcademics } from "./AcademicsShared";
import { CODE_PATTERN, defaultGraduationYear } from "./academicsModel";
import { useCourseOptions, useProgramOptions, useYearOptions } from "./useAcademicOptions";

const YEAR = z.string().refine((v) => /^\d{4}$/.test(v) && Number(v) >= 1990 && Number(v) <= 2200, "A year such as 2026");
const schema = z.object({
  code: z.string().trim().min(1, "Required").max(40, "At most 40 characters").regex(CODE_PATTERN, "Letters, digits and . _ / -"),
  name: z.string().trim().min(1, "Required").max(200, "At most 200 characters"),
  courseId: z.string().min(1, "Choose a course"),
  academicYearId: z.string().min(1, "Choose an academic year"),
  admissionYear: YEAR,
  expectedGraduationYear: z.union([z.literal(""), YEAR]),
  section: z.string().trim().max(20, "At most 20 characters"),
  capacity: z.string().trim().refine((v) => v === "" || (Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 100000), "1 to 100000"),
  status: z.enum(["ACTIVE", "PLANNED"]),
}).refine((v) => !v.expectedGraduationYear || Number(v.expectedGraduationYear) >= Number(v.admissionYear), { path: ["expectedGraduationYear"], message: "On or after the admission year" });
type Values = z.infer<typeof schema>;

/** Create or edit a batch; the course is fixed once created. */
export function BatchEditorPage() {
  const { id } = useParams();
  const api = useApi();
  const existing = useQuery({ queryKey: ["academics", "batch", id], enabled: !!id,
    queryFn: async () => unwrap(await api.GET("/api/v1/academics/batches/{id}", { params: { path: { id: id! } } })) });
  if (id && existing.isPending) return <Skeleton className="h-96 w-full" />;
  if (id && existing.isError) return <DetailError error={existing.error} what="Batch" onRetry={() => void existing.refetch()} />;
  return <BatchForm key={existing.data?.version ?? "new"} batch={existing.data} />;
}

function BatchForm({ batch }: { batch: Batch | undefined }) {
  const api = useApi();
  const labels = useOrgLabels();
  const navigate = useNavigate();
  const toast = useToast();
  const [params] = useSearchParams();
  const invalidate = useInvalidateAcademics();
  const key = useIdempotencyKey();
  const courses = useCourseOptions().filter((c) => c.status === "ACTIVE" || c.id === batch?.courseId);
  const programs = useProgramOptions();
  const years = useYearOptions().filter((y) => y.status !== "COMPLETED" || y.id === batch?.academicYearId);
  const [problem, setProblem] = useState<string | null>(null);
  const editing = !!batch;
  const noun = labels.singular("BATCH");
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: {
    code: batch?.code ?? "", name: batch?.name ?? "", courseId: batch?.courseId ?? params.get("course") ?? "", academicYearId: batch?.academicYearId ?? "",
    admissionYear: String(batch?.admissionYear ?? new Date().getFullYear()), expectedGraduationYear: batch?.expectedGraduationYear ? String(batch.expectedGraduationYear) : "",
    section: batch?.section ?? "", capacity: batch?.capacity ? String(batch.capacity) : "", status: "ACTIVE" } });
  const [courseId, admission] = useWatch({ control: form.control, name: ["courseId", "admissionYear"] });
  const course = courses.find((c) => c.id === courseId);
  const program = programs.find((p) => p.id === course?.programId);
  const graduation = defaultGraduationYear(Number(admission), program?.durationYears);
  const save = useMutation({
    mutationFn: async (v: Values) => {
      const body = { code: v.code, name: v.name, academicYearId: v.academicYearId, admissionYear: Number(v.admissionYear),
        ...(v.expectedGraduationYear ? { expectedGraduationYear: Number(v.expectedGraduationYear) } : {}),
        ...(v.section ? { section: v.section } : {}), ...(v.capacity ? { capacity: Number(v.capacity) } : {}) };
      if (editing) return unwrap(await api.PATCH("/api/v1/academics/batches/{id}", { params: { path: { id: batch.id! } }, body: { ...body, version: batch.version! } }));
      return unwrap(await api.POST("/api/v1/academics/batches", { headers: { "Idempotency-Key": key.get() }, body: { ...body, courseId: v.courseId, status: v.status } }));
    },
    onSuccess: (b) => { invalidate(); toast.show("success", editing ? `${noun} saved` : `${noun} created`, b.name); void navigate(`/academics/batches/${b.id}`); },
    onError: (e) => {
      key.rotate();
      setProblem(fieldError(e, { code_in_use: "code", invalid_code: "code", year_not_applicable: "academicYearId", year_completed: "academicYearId",
        invalid_academicYearId: "academicYearId", invalid_graduation_year: "expectedGraduationYear", invalid_admission_year: "admissionYear",
        invalid_capacity: "capacity", course_inactive: "courseId" }, (f, m) => form.setError(f as keyof Values, { message: m })));
    },
  });
  const err = form.formState.errors;
  const back = editing ? `/academics/batches/${batch.id}` : "/academics/batches";
  return (
    <>
      <div className="mb-3"><Breadcrumbs items={[{ label: "Academics", to: "/academics" }, { label: labels.plural("BATCH"), to: "/academics/batches" },
        ...(editing ? [{ label: batch.name ?? "", to: back }] : []), { label: editing ? "Edit" : `New ${noun.toLowerCase()}` }]} /></div>
      <PageHeader title={editing ? `Edit ${batch.name}` : `New ${noun.toLowerCase()}`} description={`A ${noun.toLowerCase()} is the group of students admitted to a ${labels.singular("COURSE").toLowerCase()} in a year (optionally one section).`} />
      <form noValidate onSubmit={form.handleSubmit((v) => { setProblem(null); save.mutate(v); })} className="space-y-6">
        {problem && <Alert tone="danger">{problem}</Alert>}
        <Card><CardBody className="grid grid-cols-1 gap-5 md:grid-cols-2">
          <Field label={labels.singular("COURSE")} error={err.courseId?.message} hint={editing ? "Cannot change." : undefined}>
            {(p) => <Select {...p} disabled={editing} {...form.register("courseId")}>
              {editing ? <option value={batch.courseId}>{batch.courseName}</option> : <><option value="">Choose…</option>
                {courses.map((c) => <option key={c.id} value={c.id}>{c.name} · {c.programName}</option>)}</>}</Select>}
          </Field>
          <Field label="Academic year" error={err.academicYearId?.message} hint="Organization-wide years, or years of the program's institution.">
            {(p) => <Select {...p} {...form.register("academicYearId")}><option value="">Choose…</option>
              {years.map((y) => <option key={y.id} value={y.id}>{y.name}{y.ownerName ? ` · ${y.ownerName}` : ""}</option>)}</Select>}
          </Field>
          <Field label="Code" error={err.code?.message} hint={`Unique within the ${labels.singular("COURSE").toLowerCase()}, e.g. CSE-2026-A.`}>{(p) => <Input {...p} autoComplete="off" {...form.register("code")} />}</Field>
          <Field label="Name" error={err.name?.message}>{(p) => <Input {...p} {...form.register("name")} />}</Field>
          <Field label="Admission year" error={err.admissionYear?.message}>{(p) => <Input {...p} inputMode="numeric" {...form.register("admissionYear")} />}</Field>
          <Field label="Expected graduation year (optional)" error={err.expectedGraduationYear?.message} hint={graduation ? `Leave empty for ${graduation}.` : undefined}>
            {(p) => <Input {...p} inputMode="numeric" {...form.register("expectedGraduationYear")} />}
          </Field>
          <Field label="Section (optional)" error={err.section?.message}>{(p) => <Input {...p} {...form.register("section")} />}</Field>
          <Field label="Capacity (optional)" error={err.capacity?.message}>{(p) => <Input {...p} inputMode="numeric" {...form.register("capacity")} />}</Field>
          {!editing && <Field label="Status" error={err.status?.message}>{(p) => <Select {...p} {...form.register("status")}><option value="ACTIVE">Active</option><option value="PLANNED">Planned</option></Select>}</Field>}
        </CardBody></Card>
        <div className="flex flex-wrap justify-end gap-3">
          <ButtonLink to={back}>Cancel</ButtonLink>
          <Button type="submit" loading={save.isPending}>{editing ? "Save changes" : `Create ${noun.toLowerCase()}`}</Button>
        </div>
      </form>
    </>
  );
}

export default BatchEditorPage;
